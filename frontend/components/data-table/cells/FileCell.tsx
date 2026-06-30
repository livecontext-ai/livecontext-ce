'use client';

import React, { useCallback, useRef, useState } from 'react';
import { useTranslations } from 'next-intl';
import {
  Upload, Loader2, Download, Eye, Trash2,
  FileText, Image as ImageIcon, Video, Music, File,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { fileService, getFileUrlById } from '@/lib/api/orchestrator/file.service';
import { openAuthedFileInNewTab, downloadAuthedFile } from '@/lib/utils/url-auth';
import { useAuthedObjectUrl } from '@/hooks/useAuthedObjectUrl';
import type { VisualCellProps } from './types';

// ---- helpers (mirrors FilePreview pattern) ----

function getFileIcon(mimeType: string) {
  if (mimeType.startsWith('image/')) return ImageIcon;
  if (mimeType.startsWith('video/')) return Video;
  if (mimeType.startsWith('audio/')) return Music;
  if (mimeType.includes('pdf') || mimeType.includes('document') || mimeType.includes('text')) return FileText;
  return File;
}

function getIconBg(mimeType: string): string {
  if (mimeType.startsWith('image/')) return 'bg-purple-500';
  if (mimeType.startsWith('video/')) return 'bg-red-500';
  if (mimeType.startsWith('audio/')) return 'bg-orange-500';
  if (mimeType.includes('pdf')) return 'bg-red-600';
  return 'bg-slate-500';
}

function formatSize(bytes: number): string {
  if (!bytes || bytes === 0) return '';
  const units = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  return `${(bytes / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0)} ${units[i]}`;
}

/** Check if a MIME type can be previewed in the browser. */
function canPreviewInBrowser(mimeType: string): boolean {
  return mimeType.startsWith('image/') ||
    mimeType.startsWith('video/') ||
    mimeType.startsWith('audio/') ||
    mimeType.includes('pdf') ||
    mimeType.startsWith('text/');
}

/** Parse stored cell value into displayable parts. */
function parseValue(val: any): { url?: string; name?: string; mimeType?: string; size?: number } {
  if (!val) return {};
  if (typeof val === 'string' && val.startsWith('{')) {
    try { return JSON.parse(val); } catch { /* ignore */ }
  }
  if (typeof val === 'object') return val;
  if (typeof val === 'string' && (val.startsWith('http') || val.startsWith('/api/'))) {
    const name = val.split('/').pop() || 'file';
    return { url: val, name };
  }
  return {};
}

export function FileCell({ value, isEditing, onSaveAndExit, readOnly }: VisualCellProps) {
  const t = useTranslations('dataTable');
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const parsed = parseValue(value);
  const hasFile = !!parsed.url;
  const isImage = parsed.mimeType?.startsWith('image/') ?? false;
  const previewable = canPreviewInBrowser(parsed.mimeType || '');
  const Icon = getFileIcon(parsed.mimeType || '');

  // Authenticated image preview - fetched with a Bearer header and rendered from
  // an in-memory blob: URL (no session token in the URL). See useAuthedObjectUrl.
  const { url: imagePreviewUrl, error: imageError } = useAuthedObjectUrl(
    isImage && parsed.url ? parsed.url : null,
  );

  const handleFileSelect = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    setError(null);
    setUploading(true);
    try {
      const response = await fileService.uploadGeneric(file, 'datatable');
      const proxyUrl = getFileUrlById(response.id);
      onSaveAndExit(JSON.stringify({
        url: proxyUrl,
        id: response.id,
        name: response.fileName,
        mimeType: response.mimeType,
        size: response.size,
      }));
    } catch (err) {
      setError((err as Error).message || 'Upload failed');
      setUploading(false);
    }
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const handleView = useCallback(async (e: React.MouseEvent) => {
    e.stopPropagation();
    e.preventDefault();
    if (!parsed.url) return;
    await openAuthedFileInNewTab(parsed.url);
  }, [parsed.url]);

  const handleDownload = useCallback(async (e: React.MouseEvent) => {
    e.stopPropagation();
    e.preventDefault();
    if (!parsed.url) return;
    await downloadAuthedFile(parsed.url, parsed.name || 'file');
  }, [parsed.url, parsed.name]);

  const handleRemove = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    e.preventDefault();
    onSaveAndExit('');
  }, [onSaveAndExit]);

  // ---- Editing: show upload button ----
  if (isEditing) {
    return (
      <div className="flex items-center justify-center gap-2 p-1" onClick={(e) => e.stopPropagation()}>
        {uploading ? (
          <div className="flex items-center gap-2 text-sm text-theme-secondary">
            <Loader2 className="h-4 w-4 animate-spin" />
            <span>{t('uploading')}</span>
          </div>
        ) : (
          <>
            <Button variant="outline" size="sm" onClick={() => fileInputRef.current?.click()}>
              <Upload className="h-3.5 w-3.5" />
              {t('upload')}
            </Button>
            <input ref={fileInputRef} type="file" onChange={handleFileSelect} className="hidden" />
            {error && <span className="text-xs text-red-500">{error}</span>}
          </>
        )}
      </div>
    );
  }

  // ---- No file ----
  if (!hasFile) {
    return (
      <div className="flex items-center justify-center text-xs text-theme-secondary">{t('noFile')}</div>
    );
  }

  // ---- File preview ----
  return (
    <div
      className="group/file relative w-full overflow-hidden"
      onClick={(e) => e.stopPropagation()}
    >
      <div className="rounded-lg border overflow-hidden bg-slate-50 dark:bg-slate-800/50 border-slate-200 dark:border-slate-700">
        {/* Image thumbnail */}
        {isImage && imagePreviewUrl && !imageError && (
          <div className="w-full bg-slate-100 dark:bg-slate-900/50 flex items-center justify-center">
            <img
              src={imagePreviewUrl}
              alt={parsed.name || 'preview'}
              className="max-h-16 w-full object-contain"
            />
          </div>
        )}

        <div className="flex items-center gap-1.5 p-1.5">
          {/* Icon */}
          <div className={`flex-shrink-0 w-6 h-6 rounded flex items-center justify-center ${getIconBg(parsed.mimeType || '')}`}>
            <Icon className="h-3 w-3 text-white" />
          </div>

          {/* Info */}
          <div className="flex-1 min-w-0">
            <p className="text-[11px] font-medium text-theme-primary truncate leading-tight" title={parsed.name}>
              {parsed.name || t('file')}
            </p>
            {parsed.size ? (
              <p className="text-[10px] text-theme-secondary leading-tight">{formatSize(parsed.size)}</p>
            ) : null}
          </div>

          {/* Actions: View, Download, Delete - Download/Delete disabled in read-only/snapshot mode */}
          <div className="flex items-center gap-0.5 flex-shrink-0">
            {previewable && (
              <button
                onClick={handleView}
                className="p-1 rounded text-theme-secondary hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors"
                title={t('view')}
              >
                <Eye className="h-3 w-3" />
              </button>
            )}
            {!readOnly && (
              <>
                <button
                  onClick={handleDownload}
                  className="p-1 rounded text-theme-secondary hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors"
                  title={t('download')}
                >
                  <Download className="h-3 w-3" />
                </button>
                <button
                  onClick={handleRemove}
                  className="p-1 rounded text-theme-secondary hover:bg-red-100 hover:text-red-600 dark:hover:bg-red-900/30 dark:hover:text-red-400 transition-colors opacity-0 group-hover/file:opacity-100"
                  title={t('removeFile')}
                >
                  <Trash2 className="h-3 w-3" />
                </button>
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

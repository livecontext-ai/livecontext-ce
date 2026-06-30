'use client';

import React, { useCallback, useRef, useState } from 'react';
import { useTranslations } from 'next-intl';
import { Upload, Loader2, Eye, Download, Trash2 } from 'lucide-react';
import { Button } from '@/components/ui/button';
import { ImagePreview } from '@/components/data-table/ImagePreview';
import { fileService, getFileUrlById } from '@/lib/api/orchestrator/file.service';
import { openAuthedFileInNewTab, downloadAuthedFile } from '@/lib/utils/url-auth';
import type { VisualCellProps } from './types';

/** Extract the image URL from various stored formats. */
function resolveUrl(val: any): string | undefined {
  if (!val) return undefined;
  if (typeof val === 'string' && val.startsWith('{')) {
    try {
      const parsed = JSON.parse(val);
      if (parsed.url) return parsed.url;
    } catch { /* ignore */ }
  }
  if (typeof val === 'object' && val?.url) return val.url;
  if (typeof val === 'string') {
    if (val.startsWith('http') || val.startsWith('/')) return val;
  }
  return undefined;
}

export function ImageCell({ value, rowKey, field, isEditing, onSaveAndExit, readOnly }: VisualCellProps) {
  const t = useTranslations('dataTable');
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const resolvedUrl = resolveUrl(value);
  const cellKey = `${rowKey}-${field}`;

  const handleFileSelect = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;

    setError(null);
    setUploading(true);
    try {
      const response = await fileService.uploadGeneric(file, 'datatable');
      const proxyUrl = getFileUrlById(response.id, { inline: true });
      onSaveAndExit(proxyUrl);
    } catch (err) {
      setError((err as Error).message || 'Upload failed');
      setUploading(false);
    }
    if (fileInputRef.current) fileInputRef.current.value = '';
  };

  const handleView = useCallback(async (e: React.MouseEvent) => {
    e.stopPropagation();
    e.preventDefault();
    if (!resolvedUrl) return;
    await openAuthedFileInNewTab(resolvedUrl);
  }, [resolvedUrl]);

  const handleDownload = useCallback(async (e: React.MouseEvent) => {
    e.stopPropagation();
    e.preventDefault();
    if (!resolvedUrl) return;
    await downloadAuthedFile(resolvedUrl, `image_${cellKey}`);
  }, [resolvedUrl, cellKey]);

  const handleRemove = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    e.preventDefault();
    onSaveAndExit('');
  }, [onSaveAndExit]);

  if (isEditing && !resolvedUrl) {
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
            <input
              ref={fileInputRef}
              type="file"
              accept="image/jpeg,image/png,image/gif,image/webp"
              onChange={handleFileSelect}
              className="hidden"
            />
            {error && <span className="text-xs text-red-500">{error}</span>}
          </>
        )}
      </div>
    );
  }

  if (!resolvedUrl) {
    return (
      <div className="flex items-center justify-center text-xs text-theme-secondary">{t('noImage')}</div>
    );
  }

  return (
    <div className="group/img relative flex flex-col items-center gap-1" key={`image-${cellKey}-${resolvedUrl}`} onClick={(e) => e.stopPropagation()}>
      {/* Image */}
      <div className="h-14 w-14 overflow-hidden rounded-full bg-theme-secondary">
        <ImagePreview src={resolvedUrl} alt={`${field} preview`} />
      </div>

      {/* Action buttons - shown on hover below the image; Download/Delete hidden in read-only/snapshot mode */}
      <div className="flex items-center gap-0.5 opacity-0 group-hover/img:opacity-100 transition-opacity">
        <button
          onClick={handleView}
          className="p-1 rounded text-theme-secondary hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors"
          title={t('view')}
        >
          <Eye className="h-3 w-3" />
        </button>
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
              className="p-1 rounded text-theme-secondary hover:bg-red-100 hover:text-red-600 dark:hover:bg-red-900/30 dark:hover:text-red-400 transition-colors"
              title={t('removeImage')}
            >
              <Trash2 className="h-3 w-3" />
            </button>
          </>
        )}
      </div>
    </div>
  );
}

'use client';

import React, { useCallback, useMemo, useRef, useState } from 'react';
import { useTranslations } from 'next-intl';
import {
  Upload, Loader2, Download, Eye, Trash2, Link2, FolderOpen, AlertTriangle, ArrowLeft,
  FileText, Image as ImageIcon, Video, Music, File,
} from 'lucide-react';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import dynamic from 'next/dynamic';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/components/ui/dialog';
import type { StorageExplorerEntry } from '@/lib/api/storage-api';
import { fileService } from '@/lib/api/orchestrator/file.service';
import { openAuthedFileInNewTab, downloadAuthedFile } from '@/lib/utils/url-auth';
import {
  parseAsset, assetFromUpload, assetFromStorageEntry, assetFromExternalUrl,
  toStoredAsset, assetPreviewKind, type AssetPreviewKind, type TableAsset,
} from '@/lib/datatable/assetValue';
import { AssetMediaPreview } from './AssetMediaPreview';
import type { VisualCellProps } from './types';

/**
 * The one media cell. `file` and `image` columns both render through it: they were never two data
 * contracts, only two presentations of the same stored asset, so the difference lives in
 * `display.render` and nowhere else.
 *
 * Three ways in, one value out (see {@link toStoredAsset}): upload a new file, pick one already in
 * Files, or point at an external URL.
 */

type RenderVariant = 'thumbnail' | 'card';

/**
 * The picker pulls in the whole storage explorer (its data hook, the file detail view, the
 * explorer body). A grid renders this cell once per row, so loading that eagerly would put the
 * entire Files browser in the bundle of every table page for a dialog most sessions never open.
 */
const StorageExplorerTab = dynamic(
  () => import('@/app/workflows/builder/components/inspector/StorageExplorerTab')
    .then((m) => m.StorageExplorerTab),
  { ssr: false },
);

/**
 * The icon and its colour come from the SAME classification as the preview above them, or the two
 * halves of the cell contradict each other: a `.mp4` stored as application/octet-stream (most of
 * what a workflow writes) would play as a video under a generic grey file icon. The mime-only
 * arms below are what the kinds a cell does not preview still recognise.
 */
function iconFor(kind: AssetPreviewKind, mimeType: string) {
  if (kind === 'image') return ImageIcon;
  if (kind === 'video') return Video;
  if (kind === 'audio') return Music;
  if (kind === 'pdf' || mimeType.includes('document') || mimeType.includes('text')) return FileText;
  return File;
}

function iconBg(kind: AssetPreviewKind): string {
  if (kind === 'image') return 'bg-purple-500';
  if (kind === 'video') return 'bg-red-500';
  if (kind === 'audio') return 'bg-orange-500';
  if (kind === 'pdf') return 'bg-red-600';
  return 'bg-slate-500';
}

function formatSize(bytes?: number): string {
  if (!bytes) return '';
  const units = ['B', 'KB', 'MB', 'GB'];
  const i = Math.floor(Math.log(bytes) / Math.log(1024));
  return `${(bytes / Math.pow(1024, i)).toFixed(i > 0 ? 1 : 0)} ${units[i]}`;
}

/**
 * Whether opening this file in a tab shows something rather than starting a download.
 *
 * The four media kinds, resolved by name as well as by type - which is the point: a clip stored
 * as application/octet-stream had no View button before, for the same reason it had no preview.
 * Plain text keeps its own arm because it opens fine and is not a kind a row previews. The name
 * is deliberately NOT consulted for text: our raw serve hands `.log`/`.py` back as a binary
 * stream, so a View button on those would open a tab that downloads instead of showing.
 *
 * <p>This decides what is WORTH opening, never what is SAFE to open. Safety cannot be decided
 * here: what executes is the type the server sends with the bytes, and a cell can hold a file
 * with no stored type at all (most of what a workflow writes), so any deny-list at this layer
 * passes exactly the cases it was written for. {@code openAuthedFileInNewTab} owns that, on the
 * served type, where the bytes actually are.
 */
function canPreviewInBrowser(mimeType: string | undefined, kind: AssetPreviewKind): boolean {
  return kind !== 'none' || (mimeType ?? '').toLowerCase().startsWith('text/');
}

export function AssetCell({ value, displayConfig, isEditing, onSaveAndExit, readOnly }: VisualCellProps) {
  const t = useTranslations('dataTable');
  const fileInputRef = useRef<HTMLInputElement>(null);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [urlDraft, setUrlDraft] = useState<string | null>(null);

  const asset = useMemo(() => parseAsset(value), [value]);

  // `image` columns keep their round thumbnail; everything else gets the file card. An explicit
  // display.render always wins, which is how a merged column keeps its old look.
  const variant: RenderVariant =
    displayConfig?.render === 'thumbnail' || displayConfig?.render === 'card'
      ? displayConfig.render
      : 'card';
  // Pre-filter the picker to images for a thumbnail column. Deliberately a default, not a lock:
  // the user can still widen it in the picker's own dropdown.
  const pickerFileType = variant === 'thumbnail' ? ('images' as const) : ('_all' as const);
  const acceptAttr = variant === 'thumbnail' ? 'image/*' : undefined;

  // What the cell can show of this file: a picture, a clip, a sound, a page - or nothing but its
  // type icon. One classification, the Files browser's, so a video is a video on both surfaces.
  const detectedKind = asset ? assetPreviewKind(asset) : 'none';
  // A thumbnail column IS an image column: try to render the image whatever the metadata says.
  // Many real image URLs carry neither a mime type nor an extension (an unsplash or picsum link,
  // a signed CDN URL), and the old image cell rendered those fine because it never asked. The
  // onError fallback inside the preview is what makes attempting it safe.
  const previewKind = variant === 'thumbnail' && detectedKind === 'none' ? 'image' : detectedKind;

  const commit = useCallback((next: TableAsset | null) => {
    setError(null);
    setUrlDraft(null);
    onSaveAndExit(next ? toStoredAsset(next) : '');
  }, [onSaveAndExit]);

  /**
   * Into and back out of the link row. Both directions drop whatever error is showing: it belongs
   * to the source the user just left, and leaving it up blames the one they land on.
   */
  const enterUrlMode = useCallback(() => {
    setUrlDraft('');
    setError(null);
  }, []);

  const leaveUrlMode = useCallback(() => {
    setUrlDraft(null);
    setError(null);
  }, []);

  const handleUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    setError(null);
    setUploading(true);
    try {
      const response = await fileService.uploadGeneric(file, 'datatable');
      commit(assetFromUpload(response));
    } catch (err) {
      setError((err as Error).message || t('uploadFailed'));
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const handlePick = useCallback((entry: StorageExplorerEntry) => {
    // The explorer lists folders alongside files; a folder row has no bytes to reference.
    if (entry.isFolder) return;
    setPickerOpen(false);
    commit(assetFromStorageEntry(entry));
  }, [commit]);

  const handleUrlConfirm = useCallback(() => {
    const next = assetFromExternalUrl(urlDraft ?? '');
    if (!next) {
      setError(t('assetUrlInvalid'));
      return;
    }
    commit(next);
  }, [urlDraft, commit, t]);

  const handleView = useCallback(async (e: React.MouseEvent) => {
    e.stopPropagation();
    e.preventDefault();
    if (!asset?.url) return;
    if (asset.internal) {
      await openAuthedFileInNewTab(asset.url);
    } else {
      window.open(asset.url, '_blank', 'noopener,noreferrer');
    }
  }, [asset]);

  const handleDownload = useCallback(async (e: React.MouseEvent) => {
    e.stopPropagation();
    e.preventDefault();
    if (!asset?.url) return;
    await downloadAuthedFile(asset.url, asset.name);
  }, [asset]);

  const handleRemove = useCallback((e: React.MouseEvent) => {
    e.stopPropagation();
    e.preventDefault();
    commit(null);
  }, [commit]);

  const picker = (
    <Dialog open={pickerOpen} onOpenChange={setPickerOpen}>
      <DialogContent className="max-w-3xl">
        <DialogHeader>
          <DialogTitle>{t('pickFromFilesTitle')}</DialogTitle>
        </DialogHeader>
        <div className="min-h-[320px] max-h-[60vh] flex flex-col">
          {/* Tiles by default HERE only: this is a wide modal, and for a media cell what the
              file looks like is most of the choice. The inspector's narrow file field keeps
              the dense list. */}
          <StorageExplorerTab onSelect={handlePick} initialFileType={pickerFileType} defaultView="grid" viewScope="dialog" />
        </div>
      </DialogContent>
    </Dialog>
  );

  // ---- Editing: choose a source ----
  if (isEditing && !readOnly) {
    return (
      <div className="flex flex-col items-stretch gap-1 p-1" onClick={(e) => e.stopPropagation()}>
        {uploading ? (
          <div className="flex items-center justify-center gap-2 text-sm text-theme-secondary">
            <Loader2 className="h-4 w-4 animate-spin" />
            <span>{t('uploading')}</span>
          </div>
        ) : urlDraft !== null ? (
          <div className="flex items-center gap-1">
            {/* The way out. Picking "use a link" swaps the three sources for this row, and until
                this button existed the only way back to upload/Files was the Escape key - which
                nothing on screen mentioned, so the choice read as final. */}
            <Button
              size="sm"
              variant="ghost"
              className="h-7 w-7 p-0 flex-shrink-0"
              onClick={leaveUrlMode}
              title={t('assetUrlBack')}
              aria-label={t('assetUrlBack')}
            >
              <ArrowLeft className="h-3.5 w-3.5" />
            </Button>
            <Input
              autoFocus
              value={urlDraft}
              onChange={(e) => setUrlDraft(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') handleUrlConfirm();
                if (e.key === 'Escape') leaveUrlMode();
              }}
              placeholder={t('assetUrlPlaceholder')}
              className="h-7 text-sm"
            />
            <Button size="sm" variant="outline" onClick={handleUrlConfirm}>{t('assetUrlConfirm')}</Button>
          </div>
        ) : (
          <div className="flex items-center justify-center gap-1">
            <Button variant="outline" size="sm" onClick={() => fileInputRef.current?.click()} title={t('upload')}>
              <Upload className="h-3.5 w-3.5" />
              {t('upload')}
            </Button>
            <Button variant="outline" size="sm" onClick={() => setPickerOpen(true)} title={t('pickFromFiles')}>
              <FolderOpen className="h-3.5 w-3.5" />
              {t('pickFromFiles')}
            </Button>
            <Button variant="outline" size="sm" onClick={enterUrlMode} title={t('assetUrl')}>
              <Link2 className="h-3.5 w-3.5" />
            </Button>
            <input
              ref={fileInputRef}
              type="file"
              accept={acceptAttr}
              onChange={handleUpload}
              className="hidden"
            />
          </div>
        )}
        {error && <span className="text-xs text-red-500 text-center">{error}</span>}
        {picker}
      </div>
    );
  }

  // ---- Empty ----
  if (!asset) {
    return (
      <div className="flex items-center justify-center text-xs text-theme-secondary">
        {variant === 'thumbnail' ? t('noImage') : t('noFile')}
      </div>
    );
  }

  // ---- A reference we understand but cannot resolve ----
  // Said out loud on purpose: this used to render as an empty cell, so a file deleted from Files
  // (or a cell written before the file-URL cutover) looked exactly like a cell nobody filled in.
  if (asset.origin === 'broken' || !asset.url) {
    return (
      <div className="flex items-center justify-center gap-1.5 px-1" title={t('assetUnresolvableHint')}>
        <AlertTriangle className="h-3.5 w-3.5 flex-shrink-0 text-amber-500" />
        <span className="text-xs text-theme-secondary truncate">{asset.name}</span>
      </div>
    );
  }

  // The type icon: what a cell shows when the file has no frame to show (an archive, a
  // spreadsheet), and what it falls back to when the bytes never resolve.
  // Built with createElement, not JSX: the React Compiler lint reads `<TypeIcon/>` as a component
  // created during render, since it cannot see that iconFor returns one of five module-level
  // lucide icons. Measured: the JSX form warns, this one does not.
  const TypeIcon = iconFor(detectedKind, asset.mimeType || '');
  const typeIcon = React.createElement(TypeIcon, { className: 'h-5 w-5 text-theme-secondary' });
  const badgeIcon = React.createElement(TypeIcon, { className: 'h-3 w-3 text-white' });

  const actions = (
    <>
      {canPreviewInBrowser(asset.mimeType, detectedKind) || !asset.internal ? (
        <button onClick={handleView} className="p-1 rounded text-theme-secondary hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors" title={t('view')}>
          <Eye className="h-3 w-3" />
        </button>
      ) : null}
      {!readOnly && (
        <>
          <button onClick={handleDownload} className="p-1 rounded text-theme-secondary hover:bg-slate-200 dark:hover:bg-slate-700 transition-colors" title={t('download')}>
            <Download className="h-3 w-3" />
          </button>
          <button onClick={handleRemove} className="p-1 rounded text-theme-secondary hover:bg-red-100 hover:text-red-600 dark:hover:bg-red-900/30 dark:hover:text-red-400 transition-colors" title={t('removeFile')}>
            <Trash2 className="h-3 w-3" />
          </button>
        </>
      )}
    </>
  );

  // ---- Thumbnail (what an `image` column looked like) ----
  if (variant === 'thumbnail') {
    return (
      <div className="group/asset relative flex flex-col items-center gap-1" onClick={(e) => e.stopPropagation()}>
        {/* A rounded SQUARE, not a circle: a circle crops the sides off anything that is not a
            portrait, which is most of what lands in a table (a screenshot, a product shot, a
            document page). rounded-xl is the control step of the app's radius ladder, one below
            the card surface this cell sits in. */}
        <div className="h-14 w-14 overflow-hidden rounded-xl bg-theme-secondary flex items-center justify-center">
          {previewKind !== 'none' ? (
            <AssetMediaPreview
              kind={previewKind}
              src={asset.url}
              name={asset.name}
              mimeType={asset.mimeType}
              interactive={false}
              containerClassName="h-full w-full flex items-center justify-center"
              fallback={typeIcon}
            />
          ) : typeIcon}
        </div>
        <div className="flex items-center gap-0.5 opacity-0 group-hover/asset:opacity-100 transition-opacity">
          {actions}
        </div>
      </div>
    );
  }

  // ---- Card (what a `file` column looked like) ----
  return (
    <div className="group/asset relative w-full overflow-hidden" onClick={(e) => e.stopPropagation()}>
      <div className="rounded-lg border overflow-hidden bg-slate-50 dark:bg-slate-800/50 border-slate-200 dark:border-slate-700">
        {previewKind !== 'none' && (
          <AssetMediaPreview
            kind={previewKind}
            src={asset.url}
            name={asset.name}
            mimeType={asset.mimeType}
            interactive
            containerClassName="w-full bg-slate-100 dark:bg-slate-900/50 flex items-center justify-center"
          />
        )}
        <div className="flex items-center gap-1.5 p-1.5">
          <div className={`flex-shrink-0 w-6 h-6 rounded flex items-center justify-center ${iconBg(detectedKind)}`}>
            {badgeIcon}
          </div>
          <div className="flex-1 min-w-0">
            <p className="text-[11px] font-medium text-theme-primary truncate leading-tight" title={asset.name}>
              {asset.name}
            </p>
            <p className="text-[10px] text-theme-secondary leading-tight">
              {asset.origin === 'external' ? t('assetExternal') : formatSize(asset.size)}
            </p>
          </div>
          <div className="flex items-center gap-0.5 flex-shrink-0">{actions}</div>
        </div>
      </div>
    </div>
  );
}

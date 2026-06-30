'use client';

import React, { useState, useEffect } from 'react';
import { useTranslations } from 'next-intl';
import { FileImage, FolderOpen, Wand2, X } from 'lucide-react';
import { orchestratorApi } from '@/lib/api';
import type { Interface } from '@/lib/api/orchestrator/types';
import { useSidePanelSafe } from '@/contexts/SidePanelContext';
import { StorageExplorerTab } from '@/app/workflows/builder/components/inspector/StorageExplorerTab';
import { FileDetailView } from '@/components/app/FileDetailView';
import { fileRefToUrl } from '@/lib/api/orchestrator/file.service';
import { useAuthedObjectUrl } from '@/hooks/useAuthedObjectUrl';

interface ImageGenerationVisualizeCardProps {
  interfaceId: string;
  title?: string;
}

/**
 * v3.0 image record. Either FileRef-shaped (catalog dehydrator path) OR
 * legacy inline base64 (workflow opt-out).
 */
interface GeneratedImage {
  _type?: 'file';
  path?: string;
  /** storage.storage row UUID - opaque handle for {@link fileRefToUrl}. */
  id?: string;
  name?: string;
  mimeType?: string;
  size?: number;
  url?: string;
  base64?: string;
  mime_type?: string;
  revised_prompt?: string;
}

/**
 * Inline chat card for image-generation results.
 *
 * <p><b>Visual style</b> - calqué sur {@code InterfacePreviewBlock}: a
 * rounded preview area (the image grid) with NO outer border / no
 * background tint, then a minimalist footer below carrying the full
 * prompt + count. Click anywhere on the card opens the side-panel
 * {@link FileDetailView} for the first image; click on a specific
 * thumbnail opens the detail for THAT image. Auto-opens the detail view
 * once per session per interface, only if the side panel is already open
 * (matches {@code WebSearchVisualizeCard}'s non-intrusive policy).
 */
export function ImageGenerationVisualizeCard({ interfaceId, title }: ImageGenerationVisualizeCardProps) {
  const t = useTranslations('chat');
  const [interfaceData, setInterfaceData] = useState<Interface | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const sidePanel = useSidePanelSafe();

  const [refreshKey, setRefreshKey] = useState(0);
  useEffect(() => {
    const handleModified = () => setRefreshKey(prev => prev + 1);
    window.addEventListener('imageGenerationModified', handleModified);
    return () => window.removeEventListener('imageGenerationModified', handleModified);
  }, []);

  useEffect(() => {
    let cancelled = false;
    async function load() {
      try {
        const data = await orchestratorApi.getInterface(interfaceId);
        if (!cancelled) setInterfaceData(data);
      } catch (err: any) {
        if (!cancelled) setError(err?.message || 'Failed to load');
      } finally {
        if (!cancelled) setIsLoading(false);
      }
    }
    load();
    return () => { cancelled = true; };
  }, [interfaceId, refreshKey]);

  const FILES_TAB_ID = 'files-panel';
  const isTabActive = sidePanel?.isOpen && sidePanel?.activeTabId === FILES_TAB_ID;

  /** Switch the panel to the per-file detail view. */
  const openImageDetail = React.useCallback((img: GeneratedImage) => {
    if (!sidePanel || !img.path) return;
    const fileName = img.name ?? img.path.split('/').pop();
    sidePanel.openTab({
      id: FILES_TAB_ID,
      label: fileName ?? 'Image',
      icon: <FileImage className="w-4 h-4" />,
      preferredWidth: 0.4,
      content: (
        <FileDetailView
          s3Key={img.path}
          entryId={img.id}
          fileName={img.name}
          mimeType={img.mimeType ?? img.mime_type ?? 'image/png'}
          sizeBytes={img.size}
          onBack={() => {
            sidePanel.openTab({
              id: FILES_TAB_ID,
              label: 'Files',
              icon: <FolderOpen className="w-4 h-4" />,
              preferredWidth: 0.4,
              // flat: the generated image lives under a virtual workflow folder, so the
              // folder-aware listing wouldn't surface it at root - keep the find-anywhere flat list.
              content: <StorageExplorerTab focusS3Key={img.path} flat />,
            });
          }}
        />
      ),
    });
  }, [sidePanel]);

  /** Switch the panel to the Files list (focused on a specific file). */
  const openFilesList = React.useCallback((focusS3Key?: string) => {
    if (!sidePanel) return;
    sidePanel.openTab({
      id: FILES_TAB_ID,
      label: 'Files',
      icon: <FolderOpen className="w-4 h-4" />,
      preferredWidth: 0.4,
      // flat: generated images live under virtual workflow folders (see above).
      content: <StorageExplorerTab focusS3Key={focusS3Key} flat />,
    });
  }, [sidePanel]);

  // Auto-open detail view for the FIRST image of this interface - once per
  // session per interface. Only fires when the panel is already open
  // (non-intrusive: don't pop a panel the user closed).
  const autoOpenedRef = React.useRef(false);
  useEffect(() => {
    if (autoOpenedRef.current || !sidePanel || !interfaceData) return;
    if (!sidePanel.isOpen) return;
    const data = (interfaceData.data ?? {}) as Record<string, unknown>;
    const imgs = (Array.isArray(data.images) ? data.images : []) as GeneratedImage[];
    const first = imgs.find((i) => i.path);
    if (!first) return;
    const seenKey = `lc_imggen_seen:${interfaceId}`;
    try {
      if (sessionStorage.getItem(seenKey)) { autoOpenedRef.current = true; return; }
      sessionStorage.setItem(seenKey, '1');
    } catch { /* sessionStorage unavailable → still auto-open once */ }
    autoOpenedRef.current = true;
    openImageDetail(first);
  }, [sidePanel, interfaceData, interfaceId, openImageDetail]);

  // Skeleton - same aspect-ratio shell as the loaded card so the chat
  // doesn't reflow when data arrives. Mirrors InterfacePreviewBlock.
  if (isLoading) {
    return (
      <div className="relative my-6 isolate">
        <div className="rounded-xl overflow-hidden">
          <div className="w-full animate-pulse bg-theme-secondary" style={{ aspectRatio: '16 / 10' }} />
        </div>
        <div className="flex items-center justify-between px-1 pt-2">
          <div className="flex items-center gap-2 min-w-0 flex-1">
            <FileImage className="w-4 h-4 text-theme-muted shrink-0" />
            <div className="h-4 w-32 bg-theme-tertiary rounded animate-pulse" />
          </div>
        </div>
      </div>
    );
  }

  if (error || !interfaceData) {
    return (
      <div className="relative my-6 isolate">
        <div className="rounded-xl overflow-hidden bg-theme-secondary p-4 text-sm text-theme-muted" style={{ aspectRatio: '16 / 10' }}>
          Failed to load generated images{error ? `: ${error}` : ''}
        </div>
      </div>
    );
  }

  const data = (interfaceData.data ?? {}) as Record<string, unknown>;
  const images: GeneratedImage[] = Array.isArray(data.images) ? (data.images as GeneratedImage[]) : [];
  const provider = (data.provider as string | undefined) ?? '';
  const billingModel = (data.billing_model as string | undefined) ?? '';
  const prompt = (data.prompt as string | undefined) ?? title ?? '';

  if (images.length === 0) {
    return (
      <div className="relative my-6 isolate">
        <div className="flex flex-col items-center justify-center text-theme-muted bg-theme-secondary rounded-xl" style={{ aspectRatio: '16 / 10' }}>
          <FileImage className="w-8 h-8 mb-2 opacity-50" />
          <span className="text-sm">No images returned for this generation.</span>
        </div>
      </div>
    );
  }

  // Render up to 4 thumbnails; the side panel detail view shows the full image.
  const previewImages = images.slice(0, 4);
  const defaultImg = images.find((i) => i.path) ?? images[0];

  // Click anywhere on the card → detail view for the first image. Same
  // pattern as InterfacePreviewBlock's click-anywhere-to-open. Per-
  // thumbnail buttons stopPropagation to override with their own image.
  const handleCardClick = () => {
    if (!sidePanel) return;
    if (isTabActive) {
      sidePanel.removeTab(FILES_TAB_ID);
      sidePanel.close();
      return;
    }
    if (defaultImg.path) openImageDetail(defaultImg);
    else openFilesList();
  };

  return (
    <div
      className="relative my-6 isolate cursor-pointer"
      onClick={handleCardClick}
    >
      {/* Active-tab overlay - mirrors InterfacePreviewBlock for visual parity */}
      {isTabActive && (
        <div className="absolute inset-0 z-20 bg-black/5 backdrop-blur-[3px] flex items-center justify-center rounded-xl">
          <div className="flex items-center gap-2 px-4 py-2 bg-white/90 dark:bg-slate-800/90 rounded-full">
            <X className="w-4 h-4 text-theme-primary" />
            <span className="text-sm font-medium text-theme-primary">{t('clickToClose')}</span>
          </div>
        </div>
      )}

      {/* Preview area - rounded image grid, full-resolution rendering with
          object-contain so we preserve aspect ratio (matches FileDetailView
          in the side panel). Letterbox bg = subtle theme-neutral tint. */}
      <div className="rounded-xl overflow-hidden relative">
        <div
          className={`grid gap-1 ${
            previewImages.length === 1
              ? 'grid-cols-1'
              : previewImages.length === 2
                ? 'grid-cols-2'
                : 'grid-cols-2'
          }`}
        >
          {previewImages.map((img, idx) => {
            // Per-grid-size max heights chosen so ANY single tile is large
            // (≥40 vh) - same visual scale as the side-panel detail. Single
            // image goes up to 70 vh (full chat-history height it occupies).
            const sizeClass =
                previewImages.length === 1 ? 'max-h-[70vh]' :
                previewImages.length === 2 ? 'max-h-[50vh]' :
                'max-h-[40vh]';
            return (
              <GeneratedImageThumb
                key={idx}
                img={img}
                idx={idx}
                sizeClass={sizeClass}
                onOpen={() => {
                  if (img.path) openImageDetail(img);
                  else openFilesList();
                }}
              />
            );
          })}
        </div>
      </div>

      {/* Footer - icon + count, then full prompt below in muted text. No
          background, no border (matches InterfacePreviewBlock). */}
      <div className="flex items-start gap-2 px-1 pt-2">
        <FileImage className="w-4 h-4 text-theme-muted shrink-0 mt-0.5" />
        <div className="min-w-0 flex-1">
          <div className="flex items-baseline gap-2 flex-wrap">
            <span className="text-sm font-medium text-theme-primary">
              {images.length} {images.length === 1 ? 'image' : 'images'}
            </span>
            {(provider || billingModel) && (
              <span className="text-xs text-theme-muted">
                {provider}{provider && billingModel ? ' · ' : ''}{billingModel}
              </span>
            )}
          </div>
          {prompt && (
            <p className="text-sm text-theme-secondary mt-0.5 whitespace-pre-wrap break-words">
              {prompt}
            </p>
          )}
        </div>
      </div>
    </div>
  );
}

/**
 * One thumbnail in the generation grid. A FileRef-backed image loads via a
 * header-authenticated fetch and renders from an in-memory blob: URL (no
 * session token in the URL - see {@link useAuthedObjectUrl}); a legacy inline
 * base64 image renders directly from a data: URI (passed through unchanged).
 * Extracted into its own component so the per-image hook obeys the Rules of
 * Hooks inside the grid map.
 */
function GeneratedImageThumb({
  img,
  idx,
  sizeClass,
  onOpen,
}: {
  img: GeneratedImage;
  idx: number;
  sizeClass: string;
  onOpen: () => void;
}) {
  const rawSrc = img.path
    ? (fileRefToUrl(img, { inline: true }) || null)
    : img.base64
      ? `data:${img.mime_type ?? img.mimeType ?? 'image/png'};base64,${img.base64}`
      : null;
  const { url: src } = useAuthedObjectUrl(rawSrc);
  if (!src) return null;
  return (
    <button
      type="button"
      onClick={(e) => {
        e.stopPropagation();
        onOpen();
      }}
      className="block p-0 m-0 border-0 bg-black/5 dark:bg-white/5 cursor-pointer flex items-center justify-center"
      title={img.name ?? `Generated image ${idx + 1}`}
    >
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        src={src}
        alt={img.name ?? `Generated image ${idx + 1}`}
        className={`w-full ${sizeClass} object-contain`}
        loading="lazy"
      />
    </button>
  );
}

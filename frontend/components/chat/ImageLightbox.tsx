'use client';

import React, { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { useTranslations } from 'next-intl';
import { X, Download } from 'lucide-react';
import { apiClient } from '@/lib/api/api-client';
import { getActiveOrgHeaderForRequest } from '@/lib/stores/current-org-store';
import { AuthenticatedImage } from './AuthenticatedImage';
import LoadingSpinner from '@/components/LoadingSpinner';

interface ImageLightboxProps {
  /** Whether the overlay is shown. */
  open: boolean;
  /** Close handler - fired by the X button, a backdrop click, or Escape. */
  onClose: () => void;
  /**
   * Image source. For {@code authenticated} sources this is the protected download URL
   * (e.g. {@code /api/proxy/v3/chat/attachments/{id}}) fetched with a Bearer token; for a
   * local source it's a blob/object URL (an upload preview not yet sent).
   */
  src: string;
  alt: string;
  /** Used as the download filename and the visible caption. */
  fileName?: string;
  /**
   * When true the image (and download) are fetched with the session Bearer + active-org
   * headers - chat attachments live behind auth. When false {@code src} is a local object
   * URL that needs no auth (composer upload preview).
   */
  authenticated?: boolean;
  /** Whether to show the Download button. Defaults to true. */
  downloadable?: boolean;
}

/**
 * Full-screen image viewer ("lightbox") for chat. Opened from a sent message's image
 * attachment or from the composer's pre-send upload preview, so a user can see the picture
 * large instead of the small inline thumbnail. Renders into a portal on {@code document.body}
 * so it escapes the chat scroll container and overlays the whole viewport.
 *
 * <p>Closes on the X button, a backdrop click, or Escape. The image itself swallows the click
 * so clicking on it does not dismiss. Background page scroll is locked while open.
 *
 * <p>This is deliberately a focused image-zoom overlay, NOT a reuse of {@code FileDetailView}:
 * that component is a full file-detail page keyed by a storage row id and cannot show a
 * not-yet-uploaded local file (the composer preview has no storage id until the message is sent).
 */
export function ImageLightbox({
  open,
  onClose,
  src,
  alt,
  fileName,
  authenticated = false,
  downloadable = true,
}: ImageLightboxProps) {
  const t = useTranslations('common');
  const [downloading, setDownloading] = useState(false);

  // Escape-to-close + background scroll lock, only while open.
  useEffect(() => {
    if (!open) return;
    const onKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKeyDown);
    const prevOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.body.style.overflow = prevOverflow;
    };
  }, [open, onClose]);

  const handleDownload = async () => {
    setDownloading(true);
    try {
      if (authenticated) {
        // Chat attachment: fetch the protected URL with auth, then save the blob. Mirrors
        // FileDetailView.handleDownload - <a download> can't carry the Authorization header.
        const tokenProvider = apiClient.getTokenProvider();
        const token = tokenProvider ? await tokenProvider() : null;
        const headers: Record<string, string> = { ...getActiveOrgHeaderForRequest() };
        if (token) headers['Authorization'] = `Bearer ${token}`;
        const res = await fetch(src, { headers });
        if (!res.ok) throw new Error(`HTTP ${res.status}`);
        const blob = await res.blob();
        triggerSave(blob, fileName);
      } else {
        // Local object URL (upload preview): no auth, just save it directly.
        const a = document.createElement('a');
        a.href = src;
        a.download = fileName || 'image';
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
      }
    } catch (err) {
      console.error('Image download failed:', err);
    } finally {
      setDownloading(false);
    }
  };

  if (!open || typeof document === 'undefined') return null;

  return createPortal(
    <div
      role="dialog"
      aria-modal="true"
      aria-label={fileName || alt}
      className="fixed inset-0 z-[100] flex items-center justify-center bg-black/80 backdrop-blur-sm p-4 sm:p-8"
      onClick={onClose}
    >
      {/* Top-right controls - stop propagation so a click on a button never bubbles to the backdrop. */}
      <div className="absolute top-3 right-3 flex items-center gap-2" onClick={(e) => e.stopPropagation()}>
        {downloadable && (
          <button
            type="button"
            onClick={handleDownload}
            disabled={downloading}
            aria-label={t('download')}
            title={t('download')}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-white/15 hover:bg-white/25 text-white text-sm transition-colors disabled:opacity-50"
          >
            {downloading ? <LoadingSpinner size="xs" /> : <Download className="h-4 w-4" />}
            <span>{t('download')}</span>
          </button>
        )}
        <button
          type="button"
          onClick={onClose}
          aria-label={t('close')}
          title={t('close')}
          className="inline-flex items-center justify-center h-9 w-9 rounded-lg bg-white/15 hover:bg-white/25 text-white transition-colors"
        >
          <X className="h-5 w-5" />
        </button>
      </div>

      {/* The image - swallow clicks so only the backdrop dismisses. */}
      <div className="flex flex-col items-center gap-3 max-w-full max-h-full" onClick={(e) => e.stopPropagation()}>
        {authenticated ? (
          <AuthenticatedImage
            src={src}
            alt={alt}
            className="max-w-[90vw] max-h-[80vh] object-contain rounded-lg shadow-2xl"
            fallbackClassName="w-64 h-48 rounded-lg"
          />
        ) : (
          /* eslint-disable-next-line @next/next/no-img-element */
          <img
            src={src}
            alt={alt}
            className="max-w-[90vw] max-h-[80vh] object-contain rounded-lg shadow-2xl"
          />
        )}
        {fileName && (
          <span className="text-sm text-white/80 truncate max-w-[90vw]" title={fileName}>
            {fileName}
          </span>
        )}
      </div>
    </div>,
    document.body,
  );
}

/** Save a fetched blob to disk under {@code fileName} via a transient anchor. */
function triggerSave(blob: Blob, fileName?: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName || 'image';
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

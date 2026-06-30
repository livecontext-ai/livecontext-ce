'use client';

import { useAuthedObjectUrl } from '@/hooks/useAuthedObjectUrl';

interface ImagePreviewProps {
  src: string;
  alt: string;
}

/**
 * Data-table image cell preview. Loads the image via a header-authenticated
 * fetch and renders from an in-memory blob: URL (external URLs pass through
 * unchanged) - the session token is NEVER placed in the URL. See
 * {@link useAuthedObjectUrl}.
 */
export function ImagePreview({ src, alt }: ImagePreviewProps) {
  const { url, loading, error } = useAuthedObjectUrl(src);

  if (error) {
    return (
      <div className="flex h-full w-full items-center justify-center text-[10px] text-theme-secondary">
        Failed to load
      </div>
    );
  }

  if (loading || !url) {
    return (
      <div className="flex h-full w-full items-center justify-center text-[10px] text-theme-secondary">
        Loading...
      </div>
    );
  }

  return (
    <img
      src={url}
      alt={alt}
      className="h-full w-full object-cover"
      key={url}
    />
  );
}

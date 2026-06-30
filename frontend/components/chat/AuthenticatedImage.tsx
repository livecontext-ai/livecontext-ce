'use client';

import React from 'react';
import { ImageIcon } from 'lucide-react';
import { useAuthedObjectUrl } from '@/hooks/useAuthedObjectUrl';

interface AuthenticatedImageProps {
  src: string;
  alt: string;
  className?: string;
  fallbackClassName?: string;
}

/**
 * Image that loads via a header-authenticated fetch and renders from an
 * in-memory {@code blob:} URL - the session token is NEVER placed in the image
 * URL (see {@link useAuthedObjectUrl} for the security rationale). Shows a
 * pulse placeholder while loading and a broken-image icon on failure.
 */
export function AuthenticatedImage({
  src,
  alt,
  className = '',
  fallbackClassName = ''
}: AuthenticatedImageProps) {
  const { url, loading, error } = useAuthedObjectUrl(src);

  if (loading) {
    return (
      <div className={`flex items-center justify-center bg-gray-100 dark:bg-gray-800 ${fallbackClassName || className}`}>
        <div className="animate-pulse w-8 h-8 bg-gray-200 dark:bg-gray-700 rounded" />
      </div>
    );
  }

  if (error || !url) {
    return (
      <div className={`flex items-center justify-center bg-gray-100 dark:bg-gray-800 ${fallbackClassName || className}`}>
        <ImageIcon className="w-6 h-6 text-gray-400" />
      </div>
    );
  }

  return (
    <img
      src={url}
      alt={alt}
      className={className}
      loading="lazy"
    />
  );
}

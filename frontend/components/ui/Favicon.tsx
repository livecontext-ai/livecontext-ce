'use client';

import React, { useState } from 'react';
import { Globe } from 'lucide-react';

interface FaviconProps {
  url: string;
  size?: number;
  className?: string;
  /** Optional title shown on hover (defaults to the hostname). */
  title?: string;
}

function extractHostname(url: string): string | null {
  try {
    return new URL(url).hostname;
  } catch {
    return null;
  }
}

export function Favicon({ url, size = 16, className = '', title }: FaviconProps) {
  const [errored, setErrored] = useState(false);
  const hostname = extractHostname(url);
  const fallbackTitle = title ?? hostname ?? url;

  const containerStyle: React.CSSProperties = {
    width: size,
    height: size,
    minWidth: size,
    minHeight: size,
  };

  if (!hostname || errored) {
    return (
      <span
        className={`inline-flex items-center justify-center rounded-full bg-theme-secondary text-theme-muted ${className}`}
        style={containerStyle}
        title={fallbackTitle}
        aria-hidden="true"
      >
        <Globe style={{ width: size * 0.7, height: size * 0.7 }} />
      </span>
    );
  }

  // referrerPolicy="no-referrer" strips the Referer header so Google does not
  // see which conversation/page triggered the favicon fetch. loading="lazy"
  // defers favicons in long chat histories until they scroll into view.
  return (
    <span
      className={`inline-flex items-center justify-center rounded-full bg-white overflow-hidden ${className}`}
      style={containerStyle}
      title={fallbackTitle}
    >
      {/* eslint-disable-next-line @next/next/no-img-element */}
      <img
        src={`https://s2.googleusercontent.com/s2/favicons?domain=${hostname}&sz=64`}
        width={size}
        height={size}
        alt=""
        loading="lazy"
        decoding="async"
        referrerPolicy="no-referrer"
        className="object-cover"
        onError={() => setErrored(true)}
      />
    </span>
  );
}

export default Favicon;

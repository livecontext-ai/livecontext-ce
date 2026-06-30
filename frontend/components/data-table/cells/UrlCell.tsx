'use client';

import React from 'react';
import { ExternalLink } from 'lucide-react';
import type { VisualCellProps } from './types';

export function UrlCell({ value, isEditing, onSaveAndExit }: VisualCellProps) {
  if (isEditing) {
    return (
      <input
        type="url"
        defaultValue={typeof value === 'string' ? value : ''}
        autoFocus
        className="w-full rounded-md border border-theme bg-theme-primary px-2 py-1 text-sm text-theme-primary"
        placeholder="https://example.com"
        onBlur={(event) => onSaveAndExit(event.currentTarget.value)}
        onClick={(e) => e.stopPropagation()}
      />
    );
  }

  const url = typeof value === 'string' ? value : '';
  if (!url) {
    return <span className="text-xs text-theme-secondary">No URL</span>;
  }

  // Truncate display to domain + path
  let displayText = url;
  try {
    const parsed = new URL(url.startsWith('http') ? url : `https://${url}`);
    displayText = parsed.hostname + (parsed.pathname !== '/' ? parsed.pathname : '');
  } catch {
    // keep raw
  }

  const href = url.startsWith('http') ? url : `https://${url}`;

  return (
    <div className="flex items-center justify-center gap-1.5 text-sm">
      <ExternalLink className="h-3.5 w-3.5 text-theme-secondary" />
      <a
        href={href}
        target="_blank"
        rel="noreferrer"
        className="text-theme-primary underline-offset-2 hover:underline truncate max-w-[150px]"
        onClick={(e) => e.stopPropagation()}
      >
        {displayText}
      </a>
    </div>
  );
}

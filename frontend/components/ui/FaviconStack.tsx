'use client';

import React, { useMemo } from 'react';
import { useTranslations } from 'next-intl';
import { Favicon } from './Favicon';

interface FaviconStackProps {
  urls: string[];
  /** Max favicons displayed before collapsing the remainder into a "+N" badge. */
  max?: number;
  /** Pixel size of each favicon. */
  size?: number;
  className?: string;
  /** ARIA label for the surrounding listbox (defaults to a translated "sources"). */
  ariaLabel?: string;
}

function safeHostname(url: string): string | null {
  try {
    return new URL(url).hostname || null;
  } catch {
    return null;
  }
}

function uniquePreserveOrder(urls: string[]): string[] {
  const seen = new Set<string>();
  const out: string[] = [];
  for (const u of urls) {
    if (!u) continue;
    const key = safeHostname(u) ?? u;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(u);
  }
  return out;
}

export function FaviconStack({ urls, max = 4, size = 18, className = '', ariaLabel }: FaviconStackProps) {
  const t = useTranslations('faviconStack');
  const dedupedUrls = useMemo(() => uniquePreserveOrder(urls), [urls]);

  if (dedupedUrls.length === 0) return null;

  const shown = dedupedUrls.slice(0, max);
  const remainder = dedupedUrls.length - shown.length;

  // Overlap each chip by ~35% of its size for a tight stack look.
  const overlapPx = Math.round(size * 0.35);

  return (
    <div
      className={`flex items-center shrink-0 ${className}`}
      role="list"
      aria-label={ariaLabel ?? t('sources')}
    >
      {shown.map((url, idx) => {
        const hostname = safeHostname(url) ?? url;
        return (
          <span
            key={hostname}
            role="listitem"
            aria-label={hostname}
            // Smooth halo matching the workflow node icon style:
            // `rounded-full p-0.5 bg-theme-primary dark:bg-slate-100/10`.
            // The padding+bg pair acts as a soft separator when chips overlap,
            // replacing the harder `ring-1 ring-theme` previously used.
            className="rounded-full p-0.5 bg-theme-primary dark:bg-slate-100/10 inline-flex"
            style={{ marginLeft: idx === 0 ? 0 : -overlapPx, zIndex: shown.length - idx }}
          >
            <Favicon url={url} size={size} />
          </span>
        );
      })}
      {remainder > 0 && (
        <span
          className="rounded-full p-0.5 bg-theme-primary dark:bg-slate-100/10 inline-flex items-center justify-center"
          style={{
            marginLeft: -overlapPx,
            zIndex: 0,
          }}
          title={t('moreCount', { count: remainder })}
          aria-label={t('moreCount', { count: remainder })}
        >
          <span
            className="rounded-full bg-theme-secondary text-theme-muted inline-flex items-center justify-center font-medium"
            style={{
              width: size,
              height: size,
              minWidth: size,
              minHeight: size,
              fontSize: Math.max(9, Math.round(size * 0.5)),
            }}
          >
            +{remainder}
          </span>
        </span>
      )}
    </div>
  );
}

export default FaviconStack;

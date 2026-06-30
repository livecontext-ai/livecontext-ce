'use client';

import { useCallback } from 'react';

interface LandingNavAnchorProps {
  /** id of the landing section to scroll to (e.g. "pricing", "marketplace"). */
  targetId: string;
  children: React.ReactNode;
  className?: string;
  /** Main-site origin to prefix the fallback href with, when this anchor renders
   *  off the landing (e.g. the docs subdomain). Undefined → relative `/#id`. */
  baseUrl?: string;
}

/**
 * In-page anchor for the landing header.
 *
 * Rendered by the shared `LandingShell` header, which mounts on BOTH the
 * localized landing page (`app/[locale]/page.tsx`, inside `NextIntlClientProvider`)
 * AND the non-localized public pages (`/about`, `/changelog`, `/docs`, `/legal/*`)
 * that have NO intl provider. It must therefore NOT call any intl-context hook - next-intl's
 * `useRouter()`/`usePathname()` call `useLocale()` → `useIntlContext()`, which
 * throws "No intl context found" during client render on the provider-less pages
 * and drops the whole page into the error boundary ("Something went wrong").
 *
 * Behaviour:
 * - Section present on the current page (the landing) → smooth-scroll to it and
 *   update the hash, without a full navigation.
 * - Section absent (any other page) → fall through to the native
 *   `<a href="/#targetId">`, which navigates to the home page; `HashScroller`
 *   then scrolls once the landing content is laid out. The landing's target
 *   sections live at the un-prefixed root, so a plain `/#id` is correct under
 *   `localePrefix: 'as-needed'`.
 */
export default function LandingNavAnchor({ targetId, children, className, baseUrl }: LandingNavAnchorProps) {
  const handleClick = useCallback(
    (e: React.MouseEvent<HTMLAnchorElement>) => {
      // Let the browser handle modified clicks (new tab, etc.).
      if (e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || e.button !== 0) return;
      const el = document.getElementById(targetId);
      // Section not on this page → let the native <a href="/#targetId"> navigate home.
      if (!el) return;
      e.preventDefault();
      el.scrollIntoView({ behavior: 'smooth', block: 'start' });
      window.history.replaceState(null, '', `#${targetId}`);
    },
    [targetId]
  );

  return (
    <a href={`${baseUrl ?? ''}/#${targetId}`} onClick={handleClick} className={className}>
      {children}
    </a>
  );
}

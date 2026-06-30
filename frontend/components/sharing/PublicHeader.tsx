'use client';

import LogoAnimate from '@/components/LogoAnimate';

interface PublicHeaderProps {
  title?: string;
  children?: React.ReactNode;
}

/**
 * Shared header for public pages (chat, form, conversation).
 * Shows LiveContext branding on the left, clicking redirects to the app.
 * Applications use full-screen mode without this header.
 */
export default function PublicHeader({ title, children }: PublicHeaderProps) {
  return (
    <header className="flex-shrink-0 z-10 bg-theme-secondary border-b border-theme px-4 py-2">
      <div className="max-w-4xl mx-auto flex items-center gap-3">
        <a
          href="/app"
          className="flex items-center gap-0.5 text-theme-primary hover:opacity-80 transition-opacity flex-shrink-0"
        >
          <LogoAnimate size="sm" className="text-theme-primary" />
          <span className="text-base font-light text-theme-primary livecontext-title">
            LiveContext
          </span>
        </a>
        {title && (
          <>
            <div className="w-px h-4 bg-theme-tertiary flex-shrink-0" />
            <h1 className="text-sm text-theme-secondary truncate flex-1">
              {title}
            </h1>
          </>
        )}
        {children}
      </div>
    </header>
  );
}

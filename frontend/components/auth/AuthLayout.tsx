'use client';

import React from 'react';
import Link from 'next/link';
import { Sun, Moon } from 'lucide-react';
import { useTheme } from '@/components/ThemeProvider';
import { LogoMark } from './LogoMark';

interface AuthLayoutProps {
  children: React.ReactNode;
  metaText?: string;
  metaLinkText?: string;
  metaLinkHref?: string;
}

/**
 * Shared chrome for CE auth pages (login, register) and the cloud
 * session-expired overlay. Mirrors `infra/keycloak/themes/livecontext`
 * so prod Keycloak and self-hosted CE present the same visual identity:
 * logo top-left, optional meta link + theme toggle top-right, borderless
 * centered stage below.
 */
export function AuthLayout({ children, metaText, metaLinkText, metaLinkHref }: AuthLayoutProps) {
  const { theme, toggleTheme } = useTheme();

  return (
    <div className="relative min-h-screen bg-[var(--bg-primary)]">
      <header className="absolute inset-x-0 top-0 z-10 flex items-center justify-between px-9 py-7">
        <Link
          href="/"
          aria-label="LiveContext home"
          className="inline-flex items-center text-[var(--text-primary)] transition-opacity hover:opacity-80"
        >
          <LogoMark className="h-12 w-12" />
        </Link>
        <div className="inline-flex items-center gap-3.5">
          {metaText && metaLinkText && metaLinkHref && (
            <div className="hidden items-center gap-1.5 text-[13px] text-[var(--text-secondary)] sm:inline-flex">
              <span>{metaText}</span>
              <Link
                href={metaLinkHref}
                className="border-b border-[var(--border-color)] pb-px font-medium text-[var(--text-primary)] transition-colors hover:border-[var(--text-primary)]"
              >
                {metaLinkText}
              </Link>
            </div>
          )}
          <button
            type="button"
            onClick={toggleTheme}
            aria-label="Toggle theme"
            title="Toggle theme"
            className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-[var(--border-color)] bg-[var(--bg-secondary)] text-[var(--text-secondary)] transition-all hover:-translate-y-px hover:text-[var(--text-primary)]"
          >
            {theme === 'dark' ? <Sun className="h-[15px] w-[15px]" /> : <Moon className="h-[15px] w-[15px]" />}
          </button>
        </div>
      </header>

      <main className="grid min-h-screen place-items-center px-6 pb-14 pt-24">
        <div className="w-full max-w-[400px]">{children}</div>
      </main>
    </div>
  );
}

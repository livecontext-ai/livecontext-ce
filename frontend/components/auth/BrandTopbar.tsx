'use client';

import React from 'react';
import Link from 'next/link';
import { Moon, Sun } from 'lucide-react';
import { useTheme } from '@/components/ThemeProvider';
import { cn } from '@/lib/utils';
import { LogoMark } from './LogoMark';

/**
 * The bar of the pages that stand on their own, with no app navigation: the auth pages and a
 * partner's offer page. It mirrors the Keycloak login topbar (`infra/keycloak/themes/livecontext`)
 * so the sign-in a visitor goes through looks like the page they came from: the logo mark alone on
 * the left, a few controls and the light/dark button on the right.
 *
 * @param children controls set before the theme button (a meta link, a language picker)
 * @param themeLabels the theme button's name, per the theme it switches to; English otherwise
 */
export function BrandTopbar({
  className,
  children,
  themeLabels,
}: {
  className?: string;
  children?: React.ReactNode;
  themeLabels?: { toLight: string; toDark: string };
}) {
  const { theme, toggleTheme } = useTheme();
  const themeLabel = themeLabels ? (theme === 'dark' ? themeLabels.toLight : themeLabels.toDark) : 'Toggle theme';

  return (
    <header className={cn('flex items-center justify-between px-9 py-7', className)}>
      <Link
        href="/"
        aria-label="LiveContext home"
        className="inline-flex items-center text-[var(--text-primary)] transition-opacity hover:opacity-80"
      >
        <LogoMark className="h-12 w-12" />
      </Link>
      <div className="inline-flex items-center gap-3.5">
        {children}
        <button
          type="button"
          onClick={toggleTheme}
          aria-label={themeLabel}
          title={themeLabel}
          className="inline-flex h-8 w-8 items-center justify-center rounded-lg border border-[var(--border-color)] bg-[var(--bg-secondary)] text-[var(--text-secondary)] transition-all hover:-translate-y-px hover:text-[var(--text-primary)]"
        >
          {theme === 'dark' ? <Sun className="h-[15px] w-[15px]" /> : <Moon className="h-[15px] w-[15px]" />}
        </button>
      </div>
    </header>
  );
}

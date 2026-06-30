'use client';

import { Moon, Sun } from 'lucide-react';
import { useLandingTheme } from '@/components/landing/LandingThemeProvider';

/**
 * Light/dark toggle for the docs only. It drives the docs' OWN theme provider
 * (storageKey 'docs-theme', set in the docs layout), so flipping it never changes
 * the landing/marketing pages, which stay dark. Rendered in the shared header via
 * `LandingShell`'s `headerExtra` slot - which sits inside the docs theme provider,
 * so `useLandingTheme()` resolves to the docs instance here.
 */
export function DocsThemeToggle() {
  const { theme, toggle } = useLandingTheme();
  const isDark = theme === 'dark';
  return (
    <button
      type="button"
      onClick={toggle}
      aria-label={isDark ? 'Switch to light theme' : 'Switch to dark theme'}
      title={isDark ? 'Light theme' : 'Dark theme'}
      className="inline-flex items-center justify-center w-9 h-9 rounded-full transition-colors hover:brightness-110 cursor-pointer"
      style={{
        color: 'var(--text-secondary)',
        border: '1px solid var(--border-color)',
        background: 'var(--bg-tertiary)',
      }}
    >
      {isDark ? <Sun className="w-4 h-4" aria-hidden="true" /> : <Moon className="w-4 h-4" aria-hidden="true" />}
    </button>
  );
}

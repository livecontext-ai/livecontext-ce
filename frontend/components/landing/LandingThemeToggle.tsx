'use client';

import { Moon, Sun } from 'lucide-react';
import { useLandingTheme } from '@/components/landing/LandingThemeProvider';

// Light/dark toggle for the public landing chrome (footer). It reads the
// self-contained public-site theme from `LandingThemeProvider` (decoupled from the
// app theme - see that file), NOT the app-wide ThemeProvider. It lives in the
// shared `LandingFooter`, which ALSO renders on the non-localized public pages
// (`/about`, `/contact`, `/legal/*`, `/changelog`, `/docs`) that have NO
// NextIntlClientProvider - so, like `LandingNavAnchor`, this MUST stay
// intl-context-free (no next-intl hooks, no localized navigation imports).
// Labels are plain English to match the rest of the intl-free landing chrome.
export default function LandingThemeToggle() {
  const { theme, toggle } = useLandingTheme();
  const isDark = theme === 'dark';
  const label = isDark ? 'Switch to light theme' : 'Switch to dark theme';

  return (
    <button
      type="button"
      onClick={toggle}
      aria-label={label}
      title={label}
      className="inline-flex items-center justify-center w-9 h-9 rounded-[10px] transition-all hover:brightness-110 active:scale-[0.98] cursor-pointer"
      style={{
        background: 'var(--bg-tertiary)',
        color: 'var(--text-primary)',
        border: '1px solid var(--border-color)',
      }}
    >
      {isDark ? <Sun className="w-4 h-4" aria-hidden="true" /> : <Moon className="w-4 h-4" aria-hidden="true" />}
    </button>
  );
}

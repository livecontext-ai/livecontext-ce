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
//
// Its two labels therefore arrive as PROPS, the same way the rest of the chrome gets its copy
// (see `shellLabels.ts`). `LandingFooter` always passes them, on every page: a localised page
// passes its own, an intl-free one passes DEFAULT_SHELL_LABELS. The defaults below are a safety
// net for a direct render, not a path the site takes. They are not decoration: `title` is what a visitor sees
// on hover, and the button has no text of its own, so `aria-label` IS its name: left in
// English they were the last two user-facing English strings in the footer of /fr, /de, /es,
// /pt and /zh.
export default function LandingThemeToggle({ toLight = 'Switch to light theme', toDark = 'Switch to dark theme' }: {
  /** Shown while the dark theme is active, because the click turns the lights on. */
  toLight?: string;
  toDark?: string;
} = {}) {
  const { theme, toggle } = useLandingTheme();
  const isDark = theme === 'dark';
  const label = isDark ? toLight : toDark;

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

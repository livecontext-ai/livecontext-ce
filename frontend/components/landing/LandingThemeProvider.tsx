'use client';

import { createContext, useCallback, useContext, useEffect, useState } from 'react';

// Self-contained light/dark theme for the PUBLIC site (landing + /about, /contact,
// /legal/*, /changelog, /docs). It is deliberately DECOUPLED from the app-wide
// `ThemeProvider`: the public site DEFAULTS TO DARK for every visitor and persists
// its own choice under a localStorage key, while the logged-in app keeps following
// the user's OS/preference untouched. The theme is expressed as a `dark` class on
// the `.landing-root` wrapper (palette + decorative tokens are defined for
// `.landing-root` / `.landing-root.dark` in `landingChromeStyles`), so it never
// touches <body> and never collides with the app theme.
//
// The landing/marketing pages are dark-only (respectStored=false → always dark).
// The docs section opts into a real toggle by passing its OWN `storageKey`
// ('docs-theme') + `respectStored`, so a docs light/dark choice is independent of
// the landing and never changes it.
//
// Like the rest of the landing chrome this stays intl-context-free (no next-intl),
// because the shared header/footer also render on the non-localized public pages.

type LandingTheme = 'dark' | 'light';

interface LandingThemeContextValue {
  theme: LandingTheme;
  toggle: () => void;
}

const LandingThemeContext = createContext<LandingThemeContextValue | null>(null);

// Safe accessor: falls back to the dark default (and a no-op toggle) when used
// outside the provider, mirroring `useThemeSafely`.
export function useLandingTheme(): LandingThemeContextValue {
  return useContext(LandingThemeContext) ?? { theme: 'dark', toggle: () => {} };
}

interface LandingThemeProviderProps {
  children: React.ReactNode;
  /** Extra classes merged onto the `.landing-root` wrapper (e.g. layout utilities). */
  className?: string;
  /** localStorage key for this surface's choice. Landing keeps 'landing-theme'. */
  storageKey?: string;
  /** When true, restore the persisted choice on mount (docs). Default false = always `defaultTheme`. */
  respectStored?: boolean;
  defaultTheme?: LandingTheme;
}

export default function LandingThemeProvider({
  children,
  className = '',
  storageKey = 'landing-theme',
  respectStored = false,
  defaultTheme = 'dark',
}: LandingThemeProviderProps) {
  // Start from the default on the server and the first client render (so SSR markup
  // matches and there is no hydration mismatch); a post-mount effect then restores
  // the persisted choice when `respectStored` is set (docs only).
  const [theme, setTheme] = useState<LandingTheme>(defaultTheme);

  useEffect(() => {
    if (!respectStored) return;
    try {
      const stored = window.localStorage.getItem(storageKey);
      if (stored === 'light' || stored === 'dark') setTheme(stored);
    } catch {
      /* storage unavailable (private mode) - keep the default */
    }
  }, [respectStored, storageKey]);

  useEffect(() => {
    try {
      window.localStorage.setItem(storageKey, theme);
    } catch {
      /* storage unavailable (private mode) - keep the in-memory theme */
    }
  }, [theme, storageKey]);

  const toggle = useCallback(() => {
    setTheme((current) => (current === 'dark' ? 'light' : 'dark'));
  }, []);

  const rootClass = `landing-root${theme === 'dark' ? ' dark' : ''}${className ? ` ${className}` : ''}`;

  return (
    <LandingThemeContext.Provider value={{ theme, toggle }}>
      <div className={rootClass}>{children}</div>
    </LandingThemeContext.Provider>
  );
}

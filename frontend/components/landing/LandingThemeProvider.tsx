'use client';

import { createContext, useCallback, useContext, useEffect, useState } from 'react';

// Self-contained light/dark theme for the PUBLIC site (landing + /about, /contact,
// /legal/*, /changelog, /docs). It is deliberately DECOUPLED from the app-wide
// `ThemeProvider`: the public site DEFAULTS TO LIGHT (warm white, the reassurance
// theme) for every visitor and persists its own choice under a localStorage key,
// while the logged-in app keeps following the user's OS/preference untouched. The
// theme is expressed as a `dark` class on the `.landing-root` wrapper (palette +
// decorative tokens are defined for `.landing-root` / `.landing-root.dark` in
// `landingChromeStyles`), so it never touches <body> and never collides with the
// app theme.
//
// The landing/marketing pages default to light and restore the visitor's choice
// (footer `LandingThemeToggle`, persisted under 'landing-theme'). The docs
// section keeps its OWN `storageKey` ('docs-theme'), so a docs light/dark choice
// is independent of the landing and never changes it.
//
// Like the rest of the landing chrome this stays intl-context-free (no next-intl),
// because the shared header/footer also render on the non-localized public pages.

type LandingTheme = 'dark' | 'light';

interface LandingThemeContextValue {
  theme: LandingTheme;
  toggle: () => void;
}

const LandingThemeContext = createContext<LandingThemeContextValue | null>(null);

// Safe accessor: falls back to the light default (and a no-op toggle) when used
// outside the provider, mirroring `useThemeSafely`.
export function useLandingTheme(): LandingThemeContextValue {
  return useContext(LandingThemeContext) ?? { theme: 'light', toggle: () => {} };
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
  /**
   * The language of everything inside, stamped on the wrapper this component already renders.
   *
   * <p>The ROOT layout serves `<html lang="en">` for the whole app, because it is shared with
   * every route outside the `[locale]` tree and cannot read the locale param without opting
   * the entire site out of static rendering. An inline script corrects the attribute after
   * parse, so anything running JavaScript is fine, but the SERVED document says English.
   *
   * <p>That was harmless while the landing really was English on all six URLs. It stopped
   * being harmless when the page was translated and each locale became self-canonical with
   * its own hreflang: `/fr` is now a French document whose markup claimed to be English.
   * `lang` is valid on any element and applies to its subtree, so stamping it here fixes the
   * served language for the whole public page, with no extra element and no layout change.
   * Pages outside the `[locale]` tree pass nothing and keep inheriting the document default.
   */
  lang?: string;
}

export default function LandingThemeProvider({
  children,
  className = '',
  storageKey = 'landing-theme',
  respectStored = false,
  defaultTheme = 'light',
  lang,
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
      <div className={rootClass} lang={lang}>{children}</div>
    </LandingThemeContext.Provider>
  );
}

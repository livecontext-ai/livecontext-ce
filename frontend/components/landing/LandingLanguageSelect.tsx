'use client';

import { useEffect, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { Globe } from 'lucide-react';
import { locales, type Locale } from '@/i18n/routing';
import { writeLocaleCookie } from '@/lib/utils/locale';
import { LOCALIZED_PUBLIC_PATHS } from '@/lib/seo/siteUrl';

// Language picker for the public landing chrome (footer, next to the theme toggle).
// It lives in the shared `LandingFooter`, which ALSO renders on the non-localized
// public pages (`/about`, `/contact`, `/legal/*`, `/changelog`, `/docs`) that have NO
// NextIntlClientProvider - so, like `LandingThemeToggle`, this MUST stay
// intl-context-free: plain `next/navigation` hooks (never `@/i18n/navigation`), and
// options labeled with each language's own native name (no translations needed).
const LANGUAGE_NAMES: Record<Locale, string> = {
  en: 'English',
  fr: 'Français',
  es: 'Español',
  de: 'Deutsch',
  pt: 'Português',
  zh: '中文',
};

function isLocale(value: string | undefined): value is Locale {
  return (locales as readonly string[]).includes(value ?? '');
}

export default function LandingLanguageSelect({ label = 'Language', compact = false }: {
  /** The control's accessible name. `LandingFooter` always passes one (the intl-free pages
   *  pass the English default from DEFAULT_SHELL_LABELS), so this default is a safety net for
   *  a direct render rather than a path the site takes. */
  label?: string;
  /** The topbar size (a partner offer page, beside the theme button), instead of the footer one. */
  compact?: boolean;
} = {}) {
  const pathname = usePathname();
  const router = useRouter();

  const segments = (pathname ?? '/').split('/');
  const pathLocale: Locale | null = isLocale(segments[1]) ? (segments[1] as Locale) : null;
  // localePrefix is 'as-needed': the default-locale (en) landing is served at `/`
  // with no segment - it IS a localized page (its siblings are /fr, /de, …).
  const isRootLanding = (pathname ?? '/') === '/';
  const pathWithoutLocale = pathLocale ? `/${segments.slice(2).join('/')}` : (pathname ?? '');
  const isPersonaLanding = pathWithoutLocale.startsWith('/for/');
  // A page with one URL per language: the persona landings, and the public pages listed in
  // LOCALIZED_PUBLIC_PATHS (/partners). Their bare URL is English.
  const isLocalizedPage = isPersonaLanding || (LOCALIZED_PUBLIC_PATHS as readonly string[]).includes(pathWithoutLocale);

  // On the truly non-localized public pages (/about, /legal/*, …) there is no locale
  // segment and no localized sibling - fall back to the NEXT_LOCALE cookie for the
  // displayed value. Read in an effect (not during render) to stay hydration-safe.
  const [cookieLocale, setCookieLocale] = useState<Locale>('en');
  const [isHydrated, setIsHydrated] = useState(false);
  useEffect(() => {
    const match = document.cookie.match(/(?:^|;\s*)NEXT_LOCALE=([^;]+)/);
    if (match && isLocale(match[1])) setCookieLocale(match[1]);
    setIsHydrated(true);
  }, []);

  // `/` is always the default locale (a cookie for another locale would have been
  // middleware-redirected to its prefix), so don't let the cookie override it there.
  const current: Locale = pathLocale ?? (isRootLanding || isLocalizedPage ? 'en' : cookieLocale);

  const changeLanguage = (next: Locale) => {
    if (next === current) return;
    // Persist for the whole site (landing + app) - same cookie the app settings use.
    writeLocaleCookie(next);
    setCookieLocale(next);
    if (isLocalizedPage) {
      // A fresh document replaces the page's metadata instead of retaining the
      // previous locale's canonical alongside Next.js streamed metadata.
      const prefix = next === 'en' ? '' : `/${next}`;
      window.location.assign(`${prefix}${pathWithoutLocale}${window.location.search}${window.location.hash}`);
    } else if (pathLocale) {
      const rest = segments.slice(2).join('/');
      router.push(`/${next}${rest ? `/${rest}` : ''}${window.location.search}${window.location.hash}`);
    } else if (isRootLanding) {
      // Default-locale landing at `/` → its localized sibling (`/en` redirects back
      // to `/`, but next === current already short-circuits that case).
      router.push(`/${next}${window.location.search}${window.location.hash}`);
    } else {
      // No locale in the path: the page reads the NEXT_LOCALE cookie on the server (a partner
      // offer) and re-renders in the new language; a page with a single English
      // version (/about, /legal/*) re-renders as it was, the cookie still priming the rest.
      router.refresh();
    }
  };

  return (
    <span
      className={compact
        ? 'inline-flex items-center gap-1.5 h-8 px-2 rounded-lg transition-all hover:-translate-y-px'
        : 'inline-flex items-center gap-1.5 h-9 px-2.5 rounded-[10px] transition-all hover:brightness-110'}
      style={{
        background: compact ? 'var(--bg-secondary)' : 'var(--bg-tertiary)',
        color: 'var(--text-primary)',
        border: '1px solid var(--border-color)',
      }}
    >
      <Globe className={compact ? 'w-3.5 h-3.5' : 'w-4 h-4'} aria-hidden="true" />
      <select
        aria-label={label}
        disabled={isLocalizedPage && !isHydrated}
        value={current}
        onChange={(e) => changeLanguage(e.target.value as Locale)}
        className="bg-transparent text-xs outline-none cursor-pointer"
        style={{ color: 'var(--text-primary)' }}
      >
        {locales.map((locale) => (
          <option key={locale} value={locale} style={{ color: '#111827' }}>
            {LANGUAGE_NAMES[locale]}
          </option>
        ))}
      </select>
    </span>
  );
}

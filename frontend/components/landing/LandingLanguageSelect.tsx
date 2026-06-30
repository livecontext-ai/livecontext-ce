'use client';

import { useEffect, useState } from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { Globe } from 'lucide-react';
import { locales, type Locale } from '@/i18n/routing';

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

export default function LandingLanguageSelect() {
  const pathname = usePathname();
  const router = useRouter();

  const segments = (pathname ?? '/').split('/');
  const pathLocale: Locale | null = isLocale(segments[1]) ? (segments[1] as Locale) : null;
  // localePrefix is 'as-needed': the default-locale (en) landing is served at `/`
  // with no segment - it IS a localized page (its siblings are /fr, /de, …).
  const isRootLanding = (pathname ?? '/') === '/';

  // On the truly non-localized public pages (/about, /legal/*, …) there is no locale
  // segment and no localized sibling - fall back to the NEXT_LOCALE cookie for the
  // displayed value. Read in an effect (not during render) to stay hydration-safe.
  const [cookieLocale, setCookieLocale] = useState<Locale>('en');
  useEffect(() => {
    const match = document.cookie.match(/(?:^|;\s*)NEXT_LOCALE=([^;]+)/);
    if (match && isLocale(match[1])) setCookieLocale(match[1]);
  }, []);

  // `/` is always the default locale (a cookie for another locale would have been
  // middleware-redirected to its prefix), so don't let the cookie override it there.
  const current: Locale = pathLocale ?? (isRootLanding ? 'en' : cookieLocale);

  const changeLanguage = (next: Locale) => {
    if (next === current) return;
    // Persist for the whole site (landing + app) - same cookie the app settings use.
    document.cookie = `NEXT_LOCALE=${next}; path=/; max-age=31536000; SameSite=Lax`;
    setCookieLocale(next);
    if (pathLocale) {
      const rest = segments.slice(2).join('/');
      router.push(`/${next}${rest ? `/${rest}` : ''}${window.location.search}${window.location.hash}`);
    } else if (isRootLanding) {
      // Default-locale landing at `/` → its localized sibling (`/en` redirects back
      // to `/`, but next === current already short-circuits that case).
      router.push(`/${next}${window.location.search}${window.location.hash}`);
    }
    // Truly non-localized public pages have a single (English) version: nothing to
    // navigate to - the cookie still primes the localized pages and the app.
  };

  return (
    <span
      className="inline-flex items-center gap-1.5 h-9 px-2.5 rounded-[10px] transition-all hover:brightness-110"
      style={{
        background: 'var(--bg-tertiary)',
        color: 'var(--text-primary)',
        border: '1px solid var(--border-color)',
      }}
    >
      <Globe className="w-4 h-4" aria-hidden="true" />
      <select
        aria-label="Language"
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

/**
 * Utility functions for handling locale in pathnames
 */

import { locales, type Locale } from '@/i18n/routing';

// Derived from the routing config so adding a locale can never desync these
// helpers again (the old hardcoded (en|fr|es) silently broke view detection
// for de/pt/zh users). The (?=/|$) boundary keeps '/enterprise' from being
// parsed as locale 'en' + '/terprise'.
const LOCALE_PREFIX_RE = new RegExp(`^/(${locales.join('|')})(?=/|$)`);
const LOCALE_PATH_RE = new RegExp(`^/(${locales.join('|')})(/.*)?$`);

/**
 * Remove locale prefix from pathname for comparison
 * e.g., '/fr/app/workflow' -> '/app/workflow'
 */
export const removeLocalePrefix = (path: string | null): string => {
  if (!path) return '';
  return path.replace(LOCALE_PREFIX_RE, '');
};

/**
 * Parse pathname to get both locale and normalized path
 */
export const parseLocalePath = (path: string | null): { locale: string; normalized: string } => {
  if (!path) return { locale: '', normalized: '' };
  const match = path.match(LOCALE_PATH_RE);
  if (match) {
    return { locale: match[1], normalized: match[2] || '/' };
  }
  return { locale: '', normalized: path };
};

/**
 * Build path with locale prefix (if locale is provided)
 */
export const buildLocalePath = (locale: string, path: string): string => {
  if (!locale || locale === 'en') return path; // Default locale doesn't need prefix
  return `/${locale}${path}`;
};

/**
 * Read the `NEXT_LOCALE` cookie (next-intl's locale-detection cookie) on the
 * client. Routes outside the `[locale]` tree resolve their locale from it
 * server-side (see `i18n/resolveRequestLocale.ts`).
 */
const readNextLocaleCookie = (): string | undefined => {
  const match = document.cookie.match(/(?:^|;\s*)NEXT_LOCALE=([^;]+)/);
  return match ? decodeURIComponent(match[1]) : undefined;
};

/**
 * Resolve the app's ACTIVE locale on the client, defaulting to the app default
 * ('en'). Use this for locale-aware formatting (dates, numbers) in code that
 * cannot call a React hook (plain utils, module-level helpers).
 *
 * React components should prefer next-intl's `useLocale()`: it is reactive and
 * is the authoritative value on EVERY route (including those outside the
 * `[locale]` tree, where the locale is not in the URL).
 *
 * Resolution order mirrors how the server picks the locale, so dates/numbers
 * match the rendered UI language:
 *   1. the `[locale]` URL prefix (authoritative for the localized route tree);
 *   2. the `NEXT_LOCALE` cookie (used by routes outside `[locale]`, e.g.
 *      /workflows, via resolveRequestLocale);
 *   3. the app default 'en'.
 *
 * NEVER fall back to `navigator.language`: that is the user's BROWSER language,
 * not the language they chose for the app, so a French-browser user reading the
 * /en app would see French dates and number grouping (the bug this replaces).
 */
export const getClientLocale = (): string => {
  if (typeof window === 'undefined') return 'en';
  const { locale } = parseLocalePath(window.location.pathname);
  if (locale) return locale;
  const cookieLocale = readNextLocaleCookie();
  if (cookieLocale && (locales as readonly string[]).includes(cookieLocale)) {
    return cookieLocale as Locale;
  }
  return 'en';
};

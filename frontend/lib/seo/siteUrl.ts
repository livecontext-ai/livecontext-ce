/**
 * The two origins this deployment answers on, in one place.
 *
 * <p>They were spelled out separately in the sitemap, in the robots.txt route
 * and in the docs rewrite, so moving either one would have left the other
 * copies pointing at the old name with nothing failing. The docs host itself
 * still lives in `lib/docs/docsHostRewrite.ts`, which is what routes requests
 * on it; this only turns that host into an origin.
 */
import { DOCS_HOST } from '@/lib/docs/docsHostRewrite';
import { locales, type Locale } from '@/i18n/routing';

/** Configurable at deploy time; falls back to the production domain. */
export const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? 'https://livecontext.ai';

/** The documentation subdomain, as an origin. */
export const DOCS_ORIGIN = `https://${DOCS_HOST}`;

/**
 * The landing page's path in one locale: EMPTY for English, `/<locale>` for the rest, which
 * is what `localePrefix: 'as-needed'` serves.
 *
 * <p>Empty rather than `/`: appended to the origin it gives the bare apex, which is the form
 * the sitemap has always advertised and the form Next emits for the canonical (it normalises
 * the trailing slash away). The two must match exactly, or the sitemap advertises one
 * spelling of the home page while the page names another.
 */
export function homeHref(locale: string = 'en') {
  return locale === 'en' ? '' : `/${locale}`;
}

/**
 * Open Graph wants a language_TERRITORY tag, not the bare code the routing uses.
 *
 * <p>Without it a shared link carries no language at all, and Facebook, LinkedIn and Slack
 * fall back to whatever the root layout declared, which is English.
 */
const OG_TERRITORY: Record<string, string> = {
  en: 'en_US', fr: 'fr_FR', es: 'es_ES', de: 'de_DE', pt: 'pt_PT', zh: 'zh_CN',
};

export function ogLocale(locale: string) {
  return OG_TERRITORY[locale] ?? OG_TERRITORY.en;
}

/** Every other locale, in the form Open Graph expects for `og:locale:alternate`. */
export function ogAlternateLocales(locale: string) {
  return locales.filter((value: Locale) => value !== locale).map(ogLocale);
}

/**
 * The landing's hreflang cluster.
 *
 * <p>It exists because the page's own metadata and the sitemap must agree: a sitemap that
 * advertises `/fr` while the page canonicalises to the apex asks a crawler to drop the URL
 * it was just handed. Both read this, the way the persona pages read `personaAlternates`.
 */
export function homeAlternates(siteUrl: string = SITE_URL): Record<string, string> {
  const origin = siteUrl.replace(/\/$/, '');
  return Object.fromEntries([
    ...locales.map((locale: Locale) => [locale, `${origin}${homeHref(locale)}`]),
    ['x-default', `${origin}${homeHref()}`],
  ]);
}

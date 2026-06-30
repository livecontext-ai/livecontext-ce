import type { MetadataRoute } from 'next';
import { routing } from '@/i18n/routing';
import { IS_CE } from '@/lib/edition';
import { DOCS_PAGES } from './docs/_nav';

// Configurable at deploy time; falls back to the production domain.
const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? 'https://livecontext.ai';

const { locales, defaultLocale } = routing;

/**
 * Canonical URL of a route that lives under the `app/[locale]` tree.
 * `localePrefix: 'as-needed'` (see i18n/routing.ts) serves the default locale
 * WITHOUT a prefix (`/`) and every other locale WITH one (`/fr`, `/es`, …).
 */
function localizedUrl(locale: string, path = ''): string {
  const prefix = locale === defaultLocale ? '' : `/${locale}`;
  return `${SITE_URL}${prefix}${path}`;
}

/**
 * Public-indexable surface of livecontext.ai (native Next.js sitemap).
 *
 * Included:
 *  - Landing `/` - lives under `app/[locale]`, so it is emitted once per locale
 *    with a reciprocal hreflang cluster (`alternates.languages` + `x-default`).
 *  - Marketing / legal sub-pages - these live OUTSIDE the `[locale]` tree and
 *    render at a single bare URL with runtime locale detection
 *    (i18n/resolveRequestLocale.ts), so they have no per-locale URL variants and
 *    therefore no hreflang alternates. `/changelog` is a live public nav entry
 *    (currently placeholder content) - kept at a modest priority.
 *  - Documentation - one entry per live docs page, enumerated from the docs IA
 *    (`app/docs/_nav.ts`) so the sitemap and the sidebar never drift apart.
 *
 * Excluded (also disallowed in robots.ts):
 *  - Auth-gated app (`/app/*`), `/onboarding`, `/ce-setup`, `/workflows/*`,
 *    `/billing/*`, `/local-mcp`, and token URLs (`/f`, `/s`, `/w/embed`).
 *  - `/login` and `/register`: on the cloud deployment these immediately redirect
 *    to the external OIDC provider (see app/[locale]/login/page.tsx) - content-less
 *    shims with no indexable value.
 *
 * CE deployments emit an empty sitemap: robots.ts already disallows everything
 * for self-hosted editions, and the build cannot know the deployer's domain.
 */
export default function sitemap(): MetadataRoute.Sitemap {
  if (IS_CE) {
    return [];
  }

  const now = new Date();

  // One reciprocal hreflang cluster shared by every language version of the
  // landing page. `x-default` points at the locale-detecting root.
  const landingLanguages = {
    ...Object.fromEntries(locales.map((locale) => [locale, localizedUrl(locale)])),
    'x-default': localizedUrl(defaultLocale),
  };

  const landing: MetadataRoute.Sitemap = locales.map((locale) => ({
    url: localizedUrl(locale),
    lastModified: now,
    changeFrequency: 'weekly',
    priority: locale === defaultLocale ? 1.0 : 0.9,
    alternates: { languages: landingLanguages },
  }));

  // Non-localized public sub-pages (single URL, runtime locale detection).
  const pages: MetadataRoute.Sitemap = [
    { url: `${SITE_URL}/about`, lastModified: now, changeFrequency: 'monthly', priority: 0.6 },
    { url: `${SITE_URL}/contact`, lastModified: now, changeFrequency: 'monthly', priority: 0.6 },
    { url: `${SITE_URL}/changelog`, lastModified: now, changeFrequency: 'weekly', priority: 0.5 },
    { url: `${SITE_URL}/legal/privacy`, lastModified: now, changeFrequency: 'yearly', priority: 0.3 },
    { url: `${SITE_URL}/legal/terms`, lastModified: now, changeFrequency: 'yearly', priority: 0.3 },
    { url: `${SITE_URL}/legal/mentions`, lastModified: now, changeFrequency: 'yearly', priority: 0.3 },
  ];

  // Documentation pages live on the docs.livecontext.ai subdomain at clean paths
  // (the apex /docs/* redirects there). Enumerated from the docs IA so they never
  // drift. `_nav.ts` hrefs are already clean ('/', '/agents', ...).
  const docs: MetadataRoute.Sitemap = DOCS_PAGES.map((page) => ({
    url: `https://docs.livecontext.ai${page.href === '/' ? '' : page.href}`,
    lastModified: now,
    changeFrequency: 'monthly',
    priority: page.href === '/' ? 0.6 : 0.5,
  }));

  return [...landing, ...pages, ...docs];
}

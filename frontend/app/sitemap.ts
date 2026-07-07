import type { MetadataRoute } from 'next';
import { IS_CE } from '@/lib/edition';
import { COMPARISONS } from './compare/_lib/comparisons';
import { DOCS_PAGES } from './docs/_nav';

// Configurable at deploy time; falls back to the production domain.
const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? 'https://livecontext.ai';

/**
 * Public-indexable surface of livecontext.ai (native Next.js sitemap).
 *
 * Included:
 *  - Landing `/` - ONE entry, the apex URL. The landing lives under
 *    `app/[locale]` but its content is hardcoded English on every locale
 *    (see the LandingShell contract), so the locale URLs are byte-identical
 *    duplicates: they canonicalize to the apex (app/[locale]/page.tsx) and are
 *    deliberately NOT listed here. Re-add per-locale entries WITH a reciprocal
 *    hreflang cluster only when the landing is actually translated.
 *  - `/compare/*` - the competitor comparison pages ("n8n alternative",
 *    "Zapier alternative", ...), enumerated from their content source so the
 *    sitemap never drifts from the live pages.
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

  // The landing page: one canonical URL. Locale variants serve identical
  // English content and canonicalize here (see the header comment).
  const landing: MetadataRoute.Sitemap = [
    { url: SITE_URL, lastModified: now, changeFrequency: 'weekly', priority: 1.0 },
  ];

  // Competitor comparison pages, enumerated from their single content source.
  const compare: MetadataRoute.Sitemap = [
    { url: `${SITE_URL}/compare`, lastModified: now, changeFrequency: 'monthly', priority: 0.7 },
    ...COMPARISONS.map((comparison) => ({
      url: `${SITE_URL}/compare/${comparison.slug}`,
      lastModified: now,
      changeFrequency: 'weekly' as const,
      priority: 0.8,
    })),
  ];

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

  return [...landing, ...compare, ...pages, ...docs];
}

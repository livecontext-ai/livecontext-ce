import type { MetadataRoute } from 'next';
import { IS_CE } from '@/lib/edition';
import { DOCS_PAGES } from './docs/_nav';
import {
  SITE_URL,
  compareSitemap,
  landingSitemap,
  personaSitemap,
  integrationsSitemap,
  marketplaceSitemap,
  videosSitemap,
} from '@/lib/seo/sitemapSections';
import { DOCS_ORIGIN } from '@/lib/seo/siteUrl';

/**
 * Public-indexable surface of livecontext.ai (native Next.js sitemap).
 *
 * This is the COMPLETE sitemap and stays the entry point: it is already indexed
 * and declared. The marketplace, integrations and videos sections are ALSO
 * published as files of their own (`/marketplace/sitemap.xml`, ...), built from
 * the same functions so the two cannot drift, because a search console reports
 * indexation per submitted sitemap and one file of 1096 URLs reports one
 * number. See `lib/seo/sitemapSections.ts` and `lib/seo/sitemaps.ts`.
 *
 * Included:
 *  - Landing - ONE entry PER LOCALE, each with the reciprocal hreflang cluster.
 *    It was a single apex entry while the landing was hardcoded English on every
 *    locale URL: those were byte-identical duplicates that canonicalized to the
 *    apex, so listing them advertised URLs the page asked crawlers to drop. The
 *    page is translated now and each locale canonicalizes to itself, so all six
 *    are listed (see `landingSitemap`). The site CHROME (header and footer) is
 *    still English on every locale: it renders outside the `[locale]` tree and
 *    has no intl context, which is a gap in the pages, not a reason to hide
 *    five translated pages from a crawler.
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
 *    These URLs are on the docs subdomain, which a sitemap may carry only
 *    because that host's own robots.txt declares this sitemap.
 *  - Integrations, marketplace, videos - see `lib/seo/sitemapSections.ts`.
 *
 * Excluded:
 *  - Blog (`/blog`, `/<locale>/blog`) - the section is gone: the routes, their
 *    content registry and their assets were deleted, so those URLs now 404.
 *    They are deliberately NOT disallowed in robots.txt: a crawler that cannot
 *    fetch the page never sees the 404, so already-indexed URLs would linger in
 *    the results instead of dropping out.
 *
 * Excluded and disallowed in robots.txt:
 *  - Auth-gated app (`/app/*`), `/onboarding`, `/ce-setup`, `/workflows/*`,
 *    `/billing/*`, `/local-mcp`, and token URLs (`/f`, `/s`, `/w/embed`).
 *  - `/login` and `/register`: on the cloud deployment these immediately redirect
 *    to the external OIDC provider (see app/[locale]/login/page.tsx) - content-less
 *    shims with no indexable value.
 *
 * CE deployments emit an empty sitemap: robots.txt already disallows everything
 * for self-hosted editions, and the build cannot know the deployer's domain.
 */
/**
 * Rendered per request, NOT prerendered at build time.
 *
 * The rest of the sitemap is enumerated from in-repo content, but listings
 * appear whenever someone publishes, so it cannot be frozen at build. More
 * importantly, the gateway is unreachable from the CI builder: prerendering
 * bakes a sitemap with ZERO listings, and each frontend replica then serves that
 * copy until it revalidates on its own. Verified in production: the sitemap had
 * regenerated (a fresh lastmod) and still advertised no listings, because other
 * replicas were still answering from the build-time copy.
 *
 * The catalog walk keeps its own hourly cache window, so this is one gateway
 * read per hour per replica, not one per sitemap fetch.
 */
export const dynamic = 'force-dynamic';

export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  if (IS_CE) {
    return [];
  }


  // The landing page: one entry per locale, each declaring the whole cluster.
  const landing: MetadataRoute.Sitemap = landingSitemap();

  // Non-localized public sub-pages (single URL, runtime locale detection).
  const pages: MetadataRoute.Sitemap = [
    { url: `${SITE_URL}/about`, changeFrequency: 'monthly', priority: 0.6 },
    { url: `${SITE_URL}/contact`, changeFrequency: 'monthly', priority: 0.6 },
    { url: `${SITE_URL}/models`, changeFrequency: 'weekly', priority: 0.7 },
    { url: `${SITE_URL}/changelog`, changeFrequency: 'weekly', priority: 0.5 },
    // Status mirrors live monitoring, so it changes far more often than it is
    // worth crawling; the canonical incident history lives on the externally
    // hosted status page, which is why the priority stays low.
    { url: `${SITE_URL}/status`, changeFrequency: 'daily', priority: 0.4 },
    { url: `${SITE_URL}/legal/privacy`, changeFrequency: 'yearly', priority: 0.3 },
    { url: `${SITE_URL}/legal/terms`, changeFrequency: 'yearly', priority: 0.3 },
    { url: `${SITE_URL}/legal/mentions`, changeFrequency: 'yearly', priority: 0.3 },
  ];

  // Documentation pages live on the docs.livecontext.ai subdomain at clean paths
  // (the apex /docs/* redirects there). Enumerated from the docs IA so they never
  // drift. `_nav.ts` hrefs are already clean ('/', '/agents', ...).
  const docs: MetadataRoute.Sitemap = DOCS_PAGES.map((page) => ({
    url: `${DOCS_ORIGIN}${page.href === '/' ? '' : page.href}`,
    changeFrequency: 'monthly',
    priority: page.href === '/' ? 0.6 : 0.5,
  }));

  // The three walked sections, identical to the files they are also published
  // as. Sequential rather than parallel on purpose: each keeps its own hourly
  // cache, so this runs once an hour per replica, and a burst of concurrent
  // catalog walks buys nothing.
  const marketplace = await marketplaceSitemap();
  const integrations = await integrationsSitemap();
  const videos = await videosSitemap();

  return [
    ...personaSitemap(),
    ...landing,
    ...compareSitemap(),
    ...pages,
    ...docs,
    ...marketplace,
    ...integrations,
    ...videos,
  ];
}

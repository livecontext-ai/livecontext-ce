import { SITE_URL } from './siteUrl';

/**
 * The Open Graph and Twitter card for a public page that is not the landing.
 *
 * <p><strong>Without it a page shares as the home page.</strong> The root layout declares one
 * card for the whole site, and Next MERGES metadata rather than scoping it: a page that sets
 * only `alternates.canonical` inherits the root's `og:url` (the apex) and `og:title` (the
 * generic site title). So /legal/terms posted to LinkedIn or Slack showed "LiveContext: The
 * AI automation platform" and linked to the home page, while its canonical correctly said
 * /legal/terms. Eight pages were in that state: about, contact, models, changelog, status
 * and the three legal pages.
 *
 * <p>`path` is the page's own canonical path, so the share URL and the canonical cannot
 * drift: a share that points somewhere other than the canonical is two instructions about
 * the same page that disagree.
 *
 * <p>The landing does NOT use this: it is localised, so it builds its own card per locale
 * with `og:locale` and the five alternates. This is the plain, single-language case.
 */

/**
 * The root layout applies `%s - LiveContext` to the document title, and Next does NOT apply
 * that template to `og:title`. Pages that spell the brand out already (the legal ones) must
 * not get it twice.
 */
function socialTitle(title: string) {
  return title.includes('LiveContext') ? title : `${title} - LiveContext`;
}

export function socialCard({ title, description, path }: { title: string; description: string; path: string }) {
  const resolved = socialTitle(title);
  const url = `${SITE_URL.replace(/\/$/, '')}${path}`;
  return {
    openGraph: {
      type: 'website' as const,
      siteName: 'LiveContext',
      url,
      title: resolved,
      description,
      images: [{ url: '/og-image.jpg', width: 1200, height: 630, alt: resolved }],
    },
    twitter: {
      card: 'summary_large_image' as const,
      title: resolved,
      description,
      images: ['/og-image.jpg'],
    },
  };
}

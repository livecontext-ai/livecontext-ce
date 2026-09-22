/**
 * Every sitemap this site publishes, in one list.
 *
 * `/sitemap.xml` is the complete one and stays the entry point: it is already
 * indexed and declared, and nothing here narrows it. The section sitemaps
 * repeat a slice of it on purpose, because a search console reports discovery
 * and indexation PER SUBMITTED SITEMAP: with one file holding 1096 URLs, of
 * which 89% are integration pages, "how much of the marketplace is indexed" has
 * no answer. Submitting the sections separately is what turns that into four
 * numbers instead of one.
 *
 * A URL appearing in two of these is expected and costs nothing: search engines
 * deduplicate by URL, and each sitemap still reports its own slice.
 */
export const SITEMAP_PATHS = [
  '/sitemap.xml',
  '/compare/sitemap.xml',
  '/for/sitemap.xml',
  '/marketplace/sitemap.xml',
  '/integrations/sitemap.xml',
  '/videos/sitemap.xml',
] as const;

/** Absolute URL of every sitemap, for `robots.txt` and for submission. */
export function sitemapUrls(siteUrl: string): string[] {
  return SITEMAP_PATHS.map((path) => `${siteUrl}${path}`);
}

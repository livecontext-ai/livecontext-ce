/**
 * Integration pages, as a sitemap of their own.
 *
 * <p>Every URL here is also in the complete `/sitemap.xml`, and that is the
 * point: a search console reports discovery and indexation PER SUBMITTED
 * SITEMAP, so this file is what turns "1096 URLs, some indexed" into a number
 * for this section alone. Both are built by the same function, so they cannot
 * disagree about what is public.
 *
 * <p>Declared in `lib/seo/sitemaps.ts`, which is what `robots.txt` advertises.
 */
import type { MetadataRoute } from 'next';
import { IS_CE } from '@/lib/edition';
import { integrationsSitemap } from '@/lib/seo/sitemapSections';

/** Walked from the catalog, never frozen at build. See `app/sitemap.ts`. */
export const dynamic = 'force-dynamic';

export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  if (IS_CE) {
    return [];
  }
  return integrationsSitemap();
}

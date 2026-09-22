import type { MetadataRoute } from 'next';
import { IS_CE } from '@/lib/edition';
import { personaSitemap } from '@/lib/seo/sitemapSections';

export const dynamic = 'force-dynamic';

/** The same entries as the complete sitemap, isolated for indexation reporting. */
export default async function sitemap(): Promise<MetadataRoute.Sitemap> {
  if (IS_CE) return [];
  return personaSitemap();
}

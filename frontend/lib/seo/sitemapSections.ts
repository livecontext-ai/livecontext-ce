/**
 * Shared sitemap entries for the complete sitemap and section reports.
 * Catalog walks explicitly share an hourly revalidation window.
 * lastModified is emitted only when the content source supplies a date; a
 * sitemap request or catalog fetch is not evidence that a page changed.
 */
import type { MetadataRoute } from 'next';
import { COMPARISONS } from '@/app/compare/_lib/comparisons';
import { PERSONA_KEYS, personaHref, personaAlternates } from '@/components/landing/personas/personas';
import { locales } from '@/i18n/routing';
import { fetchAllPublicPublications } from '@/lib/marketplace/publicPublications';
import { isIndexable, marketplacePath } from '@/lib/marketplace/indexability';
import { fetchAllIntegrations } from '@/lib/integrations/publicIntegrations';
import {
  integrationPath,
  isIndexableIntegration,
  isValidIntegrationSlug,
} from '@/lib/integrations/integrations';
import { videoPath, youtubeCanonicalEmbedUrl } from '@/app/videos/_lib/videos';
import { fetchPublishedVideosOrEmpty } from '@/app/videos/_lib/publicVideos';
import { SITE_URL, homeAlternates, homeHref } from './siteUrl';

export { SITE_URL };

/**
 * The landing page: one entry per locale, each carrying the whole hreflang cluster.
 *
 * <p>It used to be a single apex entry, correctly: the landing was hardcoded English on
 * every locale URL, so `/fr` was a byte-identical duplicate that canonicalised to `/`, and
 * listing it would have advertised a URL the page itself asks a crawler to drop. The page
 * is translated now and each locale canonicalises to itself, so the sitemap follows: an
 * unlisted translation is one Google has to discover on its own.
 */
export function landingSitemap(): MetadataRoute.Sitemap {
  const languages = homeAlternates(SITE_URL);
  return locales.map((locale) => ({
    url: `${SITE_URL.replace(/\/$/, '')}${homeHref(locale)}`,
    changeFrequency: 'weekly' as const,
    priority: 1.0,
    alternates: { languages },
  }));
}

/** One hour, the window every section walk shares. */
const REVALIDATE_SECONDS = 3600;

/**
 * Marketplace: the directory plus every listing that passes the indexability
 * gate. The SAME predicate drives each page's robots meta, so the sitemap can
 * never advertise a URL that then tells the crawler not to index it.
 */
export async function marketplaceSitemap(): Promise<MetadataRoute.Sitemap> {
  const { publications, truncated } = await fetchAllPublicPublications({
    revalidateSeconds: REVALIDATE_SECONDS,
  });
  if (truncated) {
    // Never let a partial catalog look like a complete one.
    console.warn(
      `[sitemap] marketplace walk stopped early after ${publications.length} listings; `
      + 'the sitemap is incomplete (page cap reached or a gateway read failed).',
    );
  }

  return [
    { url: `${SITE_URL}/marketplace`, changeFrequency: 'daily', priority: 0.8 },
    ...publications.filter(isIndexable).map((publication) => ({
      url: `${SITE_URL}${marketplacePath(publication.publicSlug)}`,
      lastModified: publication.updatedAt ? new Date(publication.updatedAt) : undefined,
      changeFrequency: 'weekly' as const,
      priority: 0.6,
    })),
  ];
}

/**
 * Integrations: the directory plus every integration page that passes the same
 * indexability gate its own `robots` meta reads.
 */
export async function integrationsSitemap(): Promise<MetadataRoute.Sitemap> {
  const { integrations, truncated } = await fetchAllIntegrations({
    revalidateSeconds: REVALIDATE_SECONDS,
  });
  if (truncated) {
    console.warn(
      `[sitemap] integration walk stopped early after ${integrations.length} integrations; `
      + 'the sitemap is incomplete (page cap reached or a gateway read failed).',
    );
  }

  return [
    { url: `${SITE_URL}/integrations`, changeFrequency: 'weekly', priority: 0.8 },
    // Also validated for SHAPE: `fetchIntegration` rejects a slug that does not
    // match locally, before any gateway call, so a slug the catalog somehow holds
    // in another shape would be advertised here and 404 on its own page.
    ...integrations
      .filter((integration) => isValidIntegrationSlug(integration.slug))
      .filter(isIndexableIntegration)
      .map((integration) => ({
        url: `${SITE_URL}${integrationPath(integration.slug)}`,
        // The catalog changes when a batch of APIs is imported, which is weeks
        // apart, not daily like a marketplace anyone can publish to.
        changeFrequency: 'monthly' as const,
        priority: 0.6,
      })),
  ];
}

/**
 * Product films: the library plus one page per PUBLISHED film, from the same
 * read the pages use, so the sitemap cannot advertise a film whose page would
 * 404. Each entry carries the `video:video` block Google reads for video
 * results; `player_loc` is the embed, `thumbnail_loc` the share image.
 */
export async function videosSitemap(): Promise<MetadataRoute.Sitemap> {
  const films = await fetchPublishedVideosOrEmpty({ revalidateSeconds: REVALIDATE_SECONDS });

  return [
    { url: `${SITE_URL}/videos`, changeFrequency: 'monthly', priority: 0.7 },
    ...films.map((video) => ({
      url: `${SITE_URL}${videoPath(video.slug)}`,
      lastModified: new Date(video.publishedAt),
      changeFrequency: 'monthly' as const,
      priority: 0.7,
      videos: [
        {
          title: video.title,
          // Absolute already, and host-checked by the library read.
          thumbnail_loc: video.shareImage,
          description: video.tagline,
          player_loc: youtubeCanonicalEmbedUrl(video.youtubeId),
          duration: video.durationSeconds,
          publication_date: video.publishedAt,
          family_friendly: 'yes' as const,
          live: 'no' as const,
        },
      ],
    })),
  ];
}

/** Comparison pages share one enumeration with their routes and index. */
export function compareSitemap(): MetadataRoute.Sitemap {
  return [
    { url: `${SITE_URL}/compare`, changeFrequency: 'monthly', priority: 0.7 },
    ...COMPARISONS.map((comparison) => ({
      url: `${SITE_URL}/compare/${comparison.slug}`,
      changeFrequency: 'weekly' as const,
      priority: 0.8,
    })),
  ];
}

/** Every translated persona URL belongs to the same reciprocal hreflang cluster. */
export function personaSitemap(): MetadataRoute.Sitemap {
  return PERSONA_KEYS.flatMap((persona) => locales.map((locale) => ({
    url: `${SITE_URL}${personaHref(persona, locale)}`,
    changeFrequency: 'monthly' as const,
    priority: 0.8,
    alternates: { languages: personaAlternates(persona, SITE_URL) },
  })));
}

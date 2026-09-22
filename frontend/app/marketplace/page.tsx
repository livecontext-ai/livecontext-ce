import type { Metadata } from 'next';
import JsonLd from '@/components/seo/JsonLd';
import { LandingShell } from '@/components/landing/LandingShell';
import { IS_CE } from '@/lib/edition';
import { fetchAllPublicPublications, fetchVerifiedPublisherHandles } from '@/lib/marketplace/publicPublications';
import { isLinkable } from '@/lib/marketplace/indexability';
import { listingListItem } from '@/lib/marketplace/listingJsonLd';
import PublicationCardSsr from './_components/PublicationCardSsr';

const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? 'https://livecontext.ai';

/**
 * Public marketplace index: the crawlable entry point into the listing tree.
 *
 * <p>It lists the WHOLE catalogue, not a first page of it. A listing that no
 * crawlable page links to is discoverable only through the sitemap, which is a
 * hint rather than a path: it earns no internal link, and the pages that do get
 * linked are the only ones that accumulate any authority. Capping this page at
 * one API page silently made every listing past the cap an orphan. Paging it
 * instead would work too, but the catalogue is in the dozens, not the
 * thousands, so one page keeps every listing exactly one click from the header.
 *
 * <p>The cards' live application previews are lazy and client-side by design -
 * see {@link PublicationCardSsr} for why the SEO payload and the preview are
 * two separate layers.
 */
/**
 * Rendered per request, NOT prerendered at build time.
 *
 * Observed in production: with `revalidate` this page is baked at build, where
 * the gateway is unreachable from the CI builder. The reader fails soft to an
 * empty list, so the EMPTY page is what gets frozen into the prerender, and
 * every frontend replica serves that until it individually revalidates. Sampling
 * the live site returned 0 or 24 cards depending on which replica answered.
 * A crawler landing on the wrong replica sees an empty marketplace.
 *
 * The upstream fetch keeps its own cache window (`next: { revalidate }` in the
 * reader), so this costs one gateway call per window per replica, not one per
 * page view.
 */
export const dynamic = 'force-dynamic';

const TITLE = 'Marketplace - ready-made AI automations';
const DESCRIPTION =
  'Browse AI agents, workflows and apps published by the LiveContext community. '
  + 'Install one in a click, or start from it and make it yours.';

export const metadata: Metadata = {
  title: TITLE,
  description: DESCRIPTION,
  alternates: { canonical: '/marketplace' },
  // Full openGraph block: Next merges metadata shallowly per top-level field,
  // so a partial override here would DROP the root layout's og:image.
  openGraph: {
    siteName: 'LiveContext',
    title: `${TITLE} - LiveContext`,
    description: DESCRIPTION,
    url: `${SITE_URL}/marketplace`,
    type: 'website',
    images: [
      {
        url: '/og-image.jpg',
        width: 1200,
        height: 630,
        alt: 'LiveContext: one message in, a working automation out.',
      },
    ],
  },
  twitter: {
    card: 'summary_large_image',
    title: `${TITLE} - LiveContext`,
    description: DESCRIPTION,
    images: ['/og-image.jpg'],
  },
  // Self-hosted deployments must never index marketing pages (same rule as the
  // landing page, /compare and /changelog).
  robots: IS_CE ? { index: false, follow: false } : undefined,
};

export default async function MarketplaceIndexPage() {
  const { publications, truncated } = await fetchAllPublicPublications();
  if (truncated) {
    // The page still renders what it got; this is the only signal that what it
    // got is not the whole catalogue. Silence here would look identical to a
    // healthy render, with listings quietly missing from the index and from
    // every internal link on it.
    console.warn(
      `[marketplace] catalogue walk stopped early after ${publications.length} listings; `
      + 'the index is incomplete (page cap reached or a gateway read failed).',
    );
  }

  // Only listings with a public slug have a URL to advertise; the rest predate
  // the slug backfill and are reachable by UUID only.
  const linkable = publications.filter(isLinkable);

  // One lookup for the whole grid: authors repeat across listings, so this is a
  // handful of handles even on a page showing the entire catalogue.
  const verifiedPublishers = await fetchVerifiedPublisherHandles(
    publications.map((publication) => publication.publisherHandle),
  );

  // ItemList tells search engines this is a listing page and gives it the
  // member URLs, which helps them discover detail pages beyond the sitemap.
  const itemListJsonLd = {
    '@context': 'https://schema.org',
    '@type': 'CollectionPage',
    name: TITLE,
    description: DESCRIPTION,
    url: `${SITE_URL}/marketplace`,
    mainEntity: {
      '@type': 'ItemList',
      numberOfItems: linkable.length,
      itemListElement: linkable.map((publication, index) =>
        listingListItem(publication, index + 1, {
          siteUrl: SITE_URL,
          slug: publication.publicSlug,
        })),
    },
  };

  return (
    <LandingShell>
      {!IS_CE && <JsonLd data={itemListJsonLd} />}
      <div className="mx-auto w-full max-w-6xl px-3 py-6 sm:px-6 md:py-10">
        <header className="mb-8">
          <h1 className="text-2xl font-semibold text-[var(--text-primary)] md:text-3xl">
            Marketplace
          </h1>
          <p className="mt-2 max-w-2xl text-sm text-[var(--text-secondary)]">{DESCRIPTION}</p>
          {publications.length > 0 && (
            <p className="mt-3 text-sm text-[var(--text-muted)]">
              {`${publications.length} published ${publications.length === 1 ? 'listing' : 'listings'}. The ones that ship an application are previewed below exactly as they run.`}
            </p>
          )}
        </header>

        {publications.length === 0 ? (
          // Reached when the gateway is unreachable as well as when the catalog
          // is genuinely empty: the read path degrades to an empty list rather
          // than failing the page.
          <p className="text-sm text-[var(--text-secondary)]">
            No published listings right now. Check back soon.
          </p>
        ) : (
          // No intl provider here on purpose: the card's live preview carries
          // its own (see MarketplaceCardPreview), so every page that renders
          // the card gets it, not just the ones whose author remembered.
          <div className="grid grid-cols-1 gap-x-5 gap-y-8 sm:grid-cols-2 lg:grid-cols-3">
            {publications.map((publication) => (
              <PublicationCardSsr
                key={publication.id}
                publication={publication}
                publisherVerified={
                  !!publication.publisherHandle
                  && verifiedPublishers.has(publication.publisherHandle.toLowerCase())
                }
              />
            ))}
          </div>
        )}
      </div>
    </LandingShell>
  );
}

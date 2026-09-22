import type { Metadata } from 'next';
import Link from 'next/link';
import { notFound } from 'next/navigation';
import JsonLd from '@/components/seo/JsonLd';
import { LandingShell } from '@/components/landing/LandingShell';
import { IS_CE } from '@/lib/edition';
import {
  fetchPublicationBySlug,
  fetchPublicationReviews,
  fetchShowcaseRender,
  fetchVerifiedPublisherHandles,
} from '@/lib/marketplace/publicPublications';
import { isIndexable, marketplacePath, metaDescription } from '@/lib/marketplace/indexability';
import { listingJsonLd } from '@/lib/marketplace/listingJsonLd';
import { buildPublicGraph } from '@/lib/marketplace/publicPlanGraph';
import { WorkflowNodeIcons } from '@/components/WorkflowNodeIcons';
import { PublisherAvatar } from '@/components/marketplace/PublisherAvatar';
import { VerifiedBadgeIcon } from '@/components/profile/VerifiedBadgeIcon';
import { formatUtcDate } from '@/lib/utils/dateFormatters';
import { Flag, Play, Star } from 'lucide-react';
import { fetchVideoForMarketplaceSlug } from '@/app/videos/_lib/publicVideos';
import { formatTimecode, videoPath } from '@/app/videos/_lib/videos';
import PublicAppPreview from './_components/PublicAppPreview';
import PublicWorkflowDiagram from './_components/PublicWorkflowDiagram';
import { NextIntlClientProvider } from 'next-intl';
import { PREVIEW_MESSAGES } from '@/lib/marketplace/previewMessages';

const SITE_URL = process.env.NEXT_PUBLIC_SITE_URL ?? 'https://livecontext.ai';

/**
 * Public listing page, addressed by its URL slug.
 *
 * ISR rather than SSG: the catalog is open-ended and grows whenever anyone
 * publishes, so there is no build-time list of slugs to pre-render. New
 * listings must be reachable without a deploy, which also means
 * `dynamicParams` stays at its default (true) here, unlike /compare
 * whose content lives in the repo.
 */
// Literal on purpose: Next requires route segment config to be statically
// analyzable, so importing PUBLIC_MARKETPLACE_REVALIDATE_SECONDS here fails the
// build with "Invalid segment configuration export". Keep the two in step.
export const revalidate = 900;

export async function generateMetadata({
  params,
}: {
  params: Promise<{ slug: string }>;
}): Promise<Metadata> {
  const { slug } = await params;
  const publication = await fetchPublicationBySlug(slug);
  if (!publication) return {};

  const url = `${SITE_URL}${marketplacePath(slug)}`;
  const description = metaDescription(publication);
  const title = `${publication.title} - LiveContext Marketplace`;

  // Thin listings render normally but stay out of the index, and out of the
  // sitemap, which reads the same predicate. Enough near-empty pages drag down
  // the ranking of the whole domain.
  const noIndex = IS_CE || !isIndexable(publication);

  return {
    // `absolute` because this title already names the brand. Left as a plain
    // string it is fed to the root layout's `title.template` ("%s - LiveContext")
    // and every listing rendered "<listing> - LiveContext Marketplace -
    // LiveContext" in the tab, the SERP and every share preview. The share
    // blocks below take no template, so they keep the plain string.
    title: { absolute: title },
    description,
    alternates: { canonical: url },
    // Both blocks are spelled out in full: Next merges metadata shallowly per
    // top-level field, so a partial override drops the root layout's values.
    // `images` is deliberately OMITTED from both: setting it here would win over
    // the file-based `opengraph-image.tsx` next to this page, and every shared
    // listing would fall back to the one generic site-wide card again.
    openGraph: {
      siteName: 'LiveContext',
      title,
      description,
      url,
      type: 'article',
    },
    twitter: {
      card: 'summary_large_image',
      title,
      description,
    },
    robots: noIndex ? { index: false, follow: true } : undefined,
  };
}

export default async function MarketplaceListingPage({
  params,
}: {
  params: Promise<{ slug: string }>;
}) {
  const { slug } = await params;
  const publication = await fetchPublicationBySlug(slug);

  // The backend answers 404 identically for an unknown slug and for a listing
  // that is not anonymously readable, so a probe cannot tell them apart. This
  // page must preserve that: one notFound() for both.
  if (!publication) notFound();

  // What the listing SHOWS, both read from data the publication already carries
  // or exposes anonymously. Neither is allowed to take the page down: a missing
  // showcase or an unreadable plan simply drops its section.
  const graph = buildPublicGraph(publication.planSnapshot);
  const [showcase, reviewPage, verifiedPublishers, film] = await Promise.all([
    publication.hasShowcase ? fetchShowcaseRender(publication.id) : Promise.resolve(null),
    fetchPublicationReviews(publication.id),
    // One author on this page. Public reviews carry no reviewer identity at all
    // (stripped server-side), so no badge is resolvable for them by design.
    fetchVerifiedPublisherHandles([publication.publisherHandle]),
    // The film that demonstrates THIS listing, if one exists. Best-effort like
    // the showcase beside it: the section disappears, the page does not. The
    // link goes both ways on purpose, because this is the page where someone
    // decides to install, and five minutes of the thing running is the best
    // argument the listing has.
    fetchVideoForMarketplaceSlug(slug),
  ]);
  const publisherVerified = !!publication.publisherHandle
    && verifiedPublishers.has(publication.publisherHandle.toLowerCase());

  const url = `${SITE_URL}${marketplacePath(slug)}`;

  // Same builder as the index's ItemList: two hand-written descriptions of one
  // entity is how structured data drifts, and nothing errors when it does.
  const softwareJsonLd = {
    '@context': 'https://schema.org',
    // The slug from the ROUTE, which is the URL this page is actually served
    // at, rather than the row's nullable field.
    ...listingJsonLd(publication, { siteUrl: SITE_URL, slug }),
  };

  const breadcrumbJsonLd = {
    '@context': 'https://schema.org',
    '@type': 'BreadcrumbList',
    itemListElement: [
      { '@type': 'ListItem', position: 1, name: 'Home', item: SITE_URL },
      { '@type': 'ListItem', position: 2, name: 'Marketplace', item: `${SITE_URL}/marketplace` },
      { '@type': 'ListItem', position: 3, name: publication.title, item: url },
    ],
  };

  const indexable = isIndexable(publication);

  return (
    <LandingShell>
      {!IS_CE && indexable && <JsonLd data={softwareJsonLd} />}
      {!IS_CE && indexable && <JsonLd data={breadcrumbJsonLd} />}

      <div className="mx-auto w-full max-w-3xl px-3 py-6 sm:px-6 md:py-10">
        <nav className="mb-4 text-sm text-[var(--text-muted)]">
          <Link href="/marketplace" className="no-underline hover:underline">
            Marketplace
          </Link>
        </nav>

        <h1 className="text-2xl font-semibold text-[var(--text-primary)] md:text-3xl">
          {publication.title}
        </h1>

        <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-[var(--text-muted)]">
          {publication.publisherName &&
            (publication.publisherHandle ? (
              // Only link when the publisher has a public handle: their profile
              // is otherwise private and the URL would 404.
              <Link href={`/u/${publication.publisherHandle}`} className="inline-flex items-center gap-1 no-underline hover:underline">
                by {publication.publisherName}
                <VerifiedBadgeIcon verified={publisherVerified} />
              </Link>
            ) : (
              <span className="inline-flex items-center gap-1">
                by {publication.publisherName}
                <VerifiedBadgeIcon verified={publisherVerified} />
              </span>
            ))}
          {publication.categoryName && <span>{publication.categoryName}</span>}
          {publication.reviewCount > 0 && (
            <span>
              {publication.averageRating.toFixed(1)} ({publication.reviewCount} reviews)
            </span>
          )}
        </div>

        {publication.description && (
          <p className="mt-6 whitespace-pre-line text-base leading-relaxed text-[var(--text-secondary)]">
            {publication.description}
          </p>
        )}

        {film && (
          <section className="mt-10">
            <h2 className="text-lg font-semibold text-[var(--text-primary)]">Watch it being built</h2>
            <p className="mt-1 text-sm text-[var(--text-muted)]">
              {`A ${formatTimecode(film.durationSeconds)} film of this automation being built and run, with its full transcript.`}
            </p>
            <Link
              href={videoPath(film.slug)}
              className="group mt-4 flex gap-4 overflow-hidden rounded-xl border border-[var(--border-color)] transition-colors hover:bg-[var(--bg-secondary)]"
            >
              <span className="relative block w-40 shrink-0 sm:w-56" style={{ aspectRatio: '16 / 9' }}>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img
                  src={film.poster}
                  alt={film.posterAlt}
                  width={1280}
                  height={720}
                  loading="lazy"
                  className="h-full w-full object-cover"
                />
                <span
                  className="absolute left-1/2 top-1/2 flex h-9 w-9 -translate-x-1/2 -translate-y-1/2 items-center justify-center rounded-full shadow-lg"
                  style={{ background: 'rgba(9, 9, 11, 0.78)' }}
                  aria-hidden="true"
                >
                  <Play className="h-3.5 w-3.5 translate-x-[1px] fill-white text-white" />
                </span>
              </span>
              <span className="flex min-w-0 flex-col justify-center py-3 pr-4">
                <span className="text-sm font-medium text-[var(--text-primary)] group-hover:underline">
                  {film.title}
                </span>
                <span className="mt-1 line-clamp-2 text-sm text-[var(--text-secondary)]">
                  {film.tagline}
                </span>
              </span>
            </Link>
          </section>
        )}

        <NextIntlClientProvider locale="en" messages={PREVIEW_MESSAGES}>
        {showcase && (
          <section className="mt-10">
            <h2 className="text-lg font-semibold text-[var(--text-primary)]">The application</h2>
            <p className="mt-1 text-sm text-[var(--text-muted)]">
              The published app, exactly as it runs. This preview is not interactive.
            </p>
            {/* No fixed height: the frame grows to the app's real content, so a
                long application is shown whole rather than cropped at the
                bottom of a box. */}
            <div className="mt-4 overflow-hidden rounded-xl border border-[var(--border-color)] bg-[var(--bg-secondary)]">
              <PublicAppPreview render={showcase} className="w-full" />
            </div>
          </section>
        )}

        {graph.nodes.length > 0 && (
          <section className="mt-10">
            <h2 className="text-lg font-semibold text-[var(--text-primary)]">The workflow</h2>
            <p className="mt-1 text-sm text-[var(--text-muted)]">
              {`What runs behind it: ${graph.nodes.length} ${graph.nodes.length === 1 ? 'step' : 'steps'}`}
              {publication.interfaceCount > 0
                ? `, ${publication.interfaceCount} ${publication.interfaceCount === 1 ? 'screen' : 'screens'}.`
                : '.'}
            </p>
            <div className="mt-4 h-[480px] overflow-hidden rounded-xl border border-[var(--border-color)] bg-[var(--bg-secondary)]">
              <PublicWorkflowDiagram graph={graph} className="h-full w-full" />
            </div>
            {publication.nodeIcons.length > 0 && (
              <div className="mt-4 flex flex-wrap items-center gap-3">
                <span className="text-sm text-[var(--text-muted)]">Uses</span>
                {/* The publication's own icon row: brand logos for the services
                    it calls, which the plan itself does not carry per node. */}
                <WorkflowNodeIcons
                  nodeIcons={publication.nodeIcons}
                  size="compact"
                  maxDisplay={8}
                  prioritizeMcpAndTriggers
                />
              </div>
            )}
          </section>
        )}
        </NextIntlClientProvider>

        <section className="mt-10">
          <h2 className="text-lg font-semibold text-[var(--text-primary)]">Published by</h2>
          <div className="mt-4 flex items-center gap-3 rounded-xl border border-[var(--border-color)] p-4">
            <PublisherAvatar
              userId={publication.publisherId}
              name={publication.publisherName}
              size={40}
              variant="neutral"
            />
            <div className="min-w-0">
              <p className="flex min-w-0 items-center gap-1.5 text-sm font-medium text-[var(--text-primary)]">
                <span className="truncate">{publication.publisherName ?? 'Anonymous publisher'}</span>
                <VerifiedBadgeIcon verified={publisherVerified} />
              </p>
              {/* Only linked when the publisher has a public handle: their
                  profile is otherwise private and the URL would 404. */}
              {publication.publisherHandle ? (
                <Link
                  href={`/u/${publication.publisherHandle}`}
                  className="text-sm text-[var(--text-muted)] no-underline hover:underline"
                >
                  {`@${publication.publisherHandle}`}
                </Link>
              ) : null}
            </div>
            {publication.publishedAt && (
              <span className="ml-auto shrink-0 text-sm text-[var(--text-muted)]">
                {formatUtcDate(publication.publishedAt, { locale: 'en' })}
              </span>
            )}
          </div>
        </section>

        {reviewPage.reviews.length > 0 && (
          <section className="mt-10">
            <h2 className="text-lg font-semibold text-[var(--text-primary)]">
              {`What people say (${reviewPage.totalElements})`}
            </h2>
            <ul className="mt-4 space-y-4 list-none p-0">
              {reviewPage.reviews.map((review) => (
                <li
                  key={review.id}
                  className="rounded-xl border border-[var(--border-color)] p-4"
                >
                  <div className="flex items-center gap-2.5">
                    {/* No user id on purpose: the public payload does not carry
                        one, so this falls back to deterministic initials. */}
                    <PublisherAvatar
                      userId={null}
                      name={review.reviewerName}
                      size={28}
                      variant="neutral"
                    />
                    <span className="text-sm font-medium text-[var(--text-primary)]">
                      {review.reviewerName ?? 'Anonymous'}
                    </span>
                    {review.rating !== null && (
                      <span
                        className="flex items-center gap-0.5"
                        aria-label={`${review.rating} out of 5`}
                      >
                        {[1, 2, 3, 4, 5].map((step) => (
                          <Star
                            key={step}
                            aria-hidden
                            className={
                              step <= review.rating!
                                ? 'h-3.5 w-3.5 fill-amber-400 text-amber-400'
                                : 'h-3.5 w-3.5 text-[var(--border-color)]'
                            }
                          />
                        ))}
                      </span>
                    )}
                    {review.createdAt && (
                      <span className="ml-auto text-xs text-[var(--text-muted)]">
                        {formatUtcDate(review.createdAt, { locale: 'en' })}
                      </span>
                    )}
                  </div>
                  {review.comment && (
                    <p className="mt-3 whitespace-pre-line text-sm leading-relaxed text-[var(--text-secondary)]">
                      {review.comment}
                    </p>
                  )}
                  {review.replyCount > 0 && (
                    <p className="mt-2 text-xs text-[var(--text-muted)]">
                      {`${review.replyCount} ${review.replyCount === 1 ? 'reply' : 'replies'}`}
                    </p>
                  )}
                </li>
              ))}
            </ul>
          </section>
        )}

        {/* Reporting rides the EXISTING public contact endpoint rather than a new
            one: /contact already accepts an `abuse` category and pre-fills from
            the query string (it was built for exactly this), and its POST is
            public and captcha-protected. So a visitor needs no account, and no
            new anonymous write route is opened. */}
        <p className="mt-10 border-t border-[var(--border-color)] pt-6 text-sm text-[var(--text-muted)]">
          <Link
            href={`/contact?category=abuse&message=${encodeURIComponent(
              `Reporting the marketplace listing "${publication.title}" (${url}).

What is wrong with it: `,
            )}`}
            className="inline-flex items-center gap-1.5 no-underline hover:underline"
          >
            <Flag className="h-3.5 w-3.5" aria-hidden />
            Report this listing
          </Link>
        </p>
      </div>
    </LandingShell>
  );
}

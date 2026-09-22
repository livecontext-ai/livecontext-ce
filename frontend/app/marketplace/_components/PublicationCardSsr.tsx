import Link from 'next/link';
import { Package, Star } from 'lucide-react';
import type { PublicPublicationSummary } from '@/lib/marketplace/publicPublications';
import { marketplacePath } from '@/lib/marketplace/indexability';
import { IS_CE } from '@/lib/edition';
import { WorkflowNodeIcons } from '@/components/WorkflowNodeIcons';
import { PublisherAvatar } from '@/components/marketplace/PublisherAvatar';
import { VerifiedBadgeIcon } from '@/components/profile/VerifiedBadgeIcon';
import { PREVIEW_MESSAGES } from '@/lib/marketplace/previewMessages';
import MarketplaceCardPreview from './MarketplaceCardPreview';

/**
 * Server-rendered marketplace card.
 *
 * Deliberately NOT `components/marketplace/PublicationCard.tsx`: that one is a
 * client component that fetches its own landing snapshot in a `useEffect`, so
 * its title and thumbnail never appear in the initial HTML. A crawler would see
 * an empty shell, which defeats the point of this page. This card takes
 * everything it indexes as props and renders to static markup.
 *
 * It also renders outside the `[locale]` tree, where there is no
 * `NextIntlClientProvider` (see the LandingShell contract), so it must not call
 * `useTranslations`. Copy is hardcoded English like /about and /compare.
 *
 * <p><b>Two layers, on purpose.</b> Everything a search engine reads - heading,
 * description, author, category, integration icons, the link itself - is in the
 * server HTML and complete on its own. The live application preview
 * ({@link MarketplaceCardPreview}) is layered ON TOP of that cover once the card
 * scrolls into view. Inlining ~80 showcase renders server-side would have been
 * megabytes of markup a crawler cannot index anyway (iframe content never is),
 * paid for in the page speed that ranking actually depends on.
 *
 * <p>The card is used on two provider-less public pages, `/marketplace` and the
 * publisher profile `/u/{handle}`. It passes {@link PREVIEW_MESSAGES} down
 * rather than letting the client preview import them, so `messages/en.json`
 * never becomes reachable from a client bundle.
 */
export default function PublicationCardSsr({
  publication,
  headingLevel = 'h2',
  publisherVerified = false,
}: {
  publication: PublicPublicationSummary;
  /**
   * Whether this listing's author carries the verified badge. Passed in rather
   * than resolved here: the page already knows every author it is about to
   * render, so one lookup covers the whole grid instead of one per card.
   */
  publisherVerified?: boolean;
  /**
   * Where the card's title sits in the page's outline. `h2` under the
   * marketplace index's `h1`; `h3` on the publisher profile, where the grid is
   * already inside an `h2` ("Published apps"). Getting this wrong does not
   * break anything visually, which is exactly why it drifts: a page with two
   * `h2` levels claiming different depths reads as a flat outline.
   */
  headingLevel?: 'h2' | 'h3';
}) {
  const {
    id,
    publicSlug,
    title,
    description,
    publisherName,
    publisherId,
    publisherHandle,
    categoryName,
    reviewCount,
    averageRating,
    useCount,
    creditsPerUse,
    hasShowcase,
    nodeIcons,
  } = publication;

  // A listing without a slug has no public URL yet (it predates the backfill).
  // Render it as plain text rather than linking to a route that would 404.
  const href = publicSlug ? marketplacePath(publicSlug) : null;

  // The cover under the live preview: the publication's own integration glyphs
  // when the backend computed them, a neutral package mark otherwise. It is
  // what a crawler, a JS-less visitor and a listing with no frozen showcase all
  // see, so it has to be a real thumbnail rather than a grey placeholder.
  const cover = (
    <div className="relative grid h-full w-full place-items-center bg-[var(--bg-secondary)]">
      <div
        aria-hidden
        className="absolute inset-0 opacity-50"
        style={{
          backgroundImage: 'radial-gradient(circle, var(--border-color) 1px, transparent 1px)',
          backgroundSize: '16px 16px',
        }}
      />
      <div className="relative">
        {nodeIcons.length > 0 ? (
          <WorkflowNodeIcons nodeIcons={nodeIcons} size="compact" maxDisplay={5} prioritizeMcpAndTriggers />
        ) : (
          <span className="grid h-12 w-12 place-items-center rounded-xl bg-[var(--bg-tertiary)]">
            <Package className="h-6 w-6 text-[var(--text-muted)]" aria-hidden />
          </span>
        )}
      </div>
    </div>
  );

  const thumbnail = (
    <div
      className="relative overflow-hidden rounded-2xl border border-[var(--border-color)] bg-[var(--bg-secondary)]"
      style={{ aspectRatio: '16 / 10' }}
    >
      {cover}
      {/* Only listings whose publisher froze a showcase have anything to run.
          Mounting the preview for the others would be a fetch that 404s. */}
      {hasShowcase && (
        <MarketplaceCardPreview publicationId={id} messages={PREVIEW_MESSAGES} />
      )}
    </div>
  );

  const Heading = headingLevel;

  // Same footer as the in-app card (`PublicationCard`): title with the rating
  // beside it, the description, then the publisher chip (avatar + name) with
  // the integration glyphs right next to it, so the two surfaces read alike.
  const body = (
    <>
      {thumbnail}
      <div className="mt-3 flex items-center gap-1.5 min-w-0">
        <Heading className="text-base font-semibold leading-snug text-[var(--text-primary)] truncate">
          {title}
        </Heading>
        {/* Ratings are only meaningful once someone has actually rated it: a
            bare "0.0" on every new listing reads as a bad score, not as
            "no reviews yet". */}
        {reviewCount > 0 && (
          <span className="inline-flex shrink-0 items-center gap-1 text-xs text-[var(--text-muted)]">
            <Star className="h-3 w-3 fill-amber-400 text-amber-400" aria-hidden />
            {averageRating.toFixed(1)}
            <span>({reviewCount})</span>
          </span>
        )}
      </div>
      {description && (
        <p className="mt-1 line-clamp-2 text-sm text-[var(--text-secondary)]">{description}</p>
      )}
    </>
  );

  const publisherChip = (
    <span className="inline-flex items-center gap-1.5 min-w-0">
      <PublisherAvatar userId={publisherId} name={publisherName} />
      <span className="truncate text-xs text-[var(--text-secondary)]">{publisherName || 'Anonymous'}</span>
      {/* Outside the [locale] tree there is no translator, so the badge keeps its
          English default label - same rule as the rest of this card's copy. */}
      <VerifiedBadgeIcon verified={publisherVerified} size="xs" />
    </span>
  );

  return (
    <article className="flex h-full flex-col">
      {href ? (
        // The whole tile is the link: one destination per card, so the crawler
        // sees a single strong internal link rather than three competing ones.
        <Link href={href} className="group block no-underline">
          {body}
        </Link>
      ) : (
        body
      )}

      {/* mt-auto: grid cells stretch to the tallest card in the row, so the
          meta rows line up across a row instead of floating under descriptions
          of different lengths. */}
      <div className="mt-auto pt-2 space-y-1">
        <div className="flex items-center gap-1.5 min-w-0">
          {publisherName &&
            (publisherHandle ? (
              // Only link when the publisher has a public handle: their profile is
              // otherwise private and the URL would 404.
              <Link href={`/u/${publisherHandle}`} className="no-underline hover:underline min-w-0">
                {publisherChip}
              </Link>
            ) : (
              publisherChip
            ))}
          {/* Integration glyphs sit right next to the publisher, like the in-app
              card: "published by X, built with these integrations". */}
          {nodeIcons.length > 0 && (
            <WorkflowNodeIcons
              nodeIcons={nodeIcons}
              maxDisplay={3}
              prioritizeMcpAndTriggers
              size="inline"
              className="shrink-0"
            />
          )}
        </div>
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-[var(--text-muted)]">
          {categoryName && <span>{categoryName}</span>}
          {useCount > 0 && <span>{`${useCount} ${useCount === 1 ? 'install' : 'installs'}`}</span>}
          {/* Same rule as the in-app card (`PricePill`): a self-hosted install
              prices in dollars, managed cloud in credits. Reading it off the
              edition rather than hardcoding "credits" keeps the two surfaces
              from telling a CE visitor a different currency. */}
          <span className="ml-auto font-medium text-[var(--text-secondary)]">
            {creditsPerUse > 0
              ? (IS_CE ? `$${creditsPerUse} / run` : `${creditsPerUse} credits / run`)
              : 'Free'}
          </span>
        </div>
      </div>
    </article>
  );
}

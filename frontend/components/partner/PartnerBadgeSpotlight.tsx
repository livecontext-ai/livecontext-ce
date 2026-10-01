import { Bell, MessageCircle, Search, Star, Store, ZoomIn } from 'lucide-react';
import PublicationCardSsr from '@/app/marketplace/_components/PublicationCardSsr';
import { PublisherAvatar } from '@/components/marketplace/PublisherAvatar';
import { PartnerBadgeIcon } from '@/components/profile/PartnerBadgeIcon';
import type { PublicPublicationSummary } from '@/lib/marketplace/publicPublications';
import { PartnerTierChip } from './PartnerTierChip';

/**
 * The illustrative partner of the /partners page, a fictional agency: the hero card and the badge
 * spotlight show the same one. It has no account behind it, so it links nowhere.
 *
 * <p>`avatar` is a placeholder (one of the product's illustrated avatars): drop the final
 * portrait in `public/partners/examples/` and point `avatar` at it.
 */
export const PARTNER_EXAMPLE = {
  publisher: 'Northwind Automation',
  handle: 'northwind',
  avatar: '/avatars/avatar-2.svg',
  rating: 4.9,
  reviews: 41,
  apps: 12,
  icons: ['gmail', 'quickbooks', 'openai'],
} as const;

/** How many real listings flank the partner's app: one cropped on each side. */
const NEIGHBOURS = 2;

/**
 * The real marketplace listings drawn on each side of the partner's app, greyed out: apps with
 * an icon row (so the card has a cover), most used first. Rendered without a link and without
 * their live preview, since they are scenery here, not destinations.
 */
export function pickNeighbours(publications: readonly PublicPublicationSummary[]): PublicPublicationSummary[] {
  return publications
    .filter((p) => p.publicationType === 'APPLICATION' && p.nodeIcons.length > 0)
    .sort((a, b) => b.useCount - a.useCount)
    .slice(0, NEIGHBOURS)
    .map((p) => ({ ...p, publicSlug: null, hasShowcase: false }));
}

function partnerListing(title: string, description: string): PublicPublicationSummary {
  return {
    id: 'partner-example-listing',
    // No slug and no handle: the fictional partner links nowhere, so it can never land on a 404
    // or on someone real.
    publicSlug: null,
    title,
    description,
    publisherName: PARTNER_EXAMPLE.publisher,
    publisherId: null,
    publisherHandle: null,
    publisherAvatarUrl: null,
    categorySlug: null,
    categoryName: null,
    averageRating: PARTNER_EXAMPLE.rating,
    reviewCount: PARTNER_EXAMPLE.reviews,
    useCount: 0,
    publishedAt: null,
    updatedAt: null,
    publicationType: 'APPLICATION',
    categoryColor: null,
    displayMode: 'APPLICATION',
    creditsPerUse: 0,
    hasShowcase: false,
    nodeIcons: PARTNER_EXAMPLE.icons.map((slug, i) => ({ nodeId: `example-${i}`, nodeKind: 'mcp', iconSlug: slug, isMcp: true })),
    agentCount: 0,
    interfaceCount: 1,
    workflowCount: 1,
    skillCount: 0,
    datasourceCount: 0,
    planSnapshot: null,
  };
}

export interface BadgeSpotlightCopy {
  badge: string;
  /** The frame's title: "LiveContext marketplace". */
  marketplace: string;
  /** The frame's search field: "Search the marketplace". */
  search: string;
  /** The callout above the partner's card: "Your app". */
  you: string;
  appTitle: string;
  appDescription: string;
  /** The callout above the zoomed profile: "Your profile". */
  profile: string;
  /** "Platinum partner": the tier chip the real profile shows next to the name. */
  profileTier: string;
  /** The real profile's two actions: "Subscribe" and "Message". */
  follow: string;
  message: string;
  /** "12 published apps". */
  profileApps: string;
  /** "4.9 (41 reviews)", the rating formatted for the locale. */
  profileRating: string;
}

const SPOTLIGHT_BADGE_GLOW = '.lc-spotlight-card [data-badge="partner"]{transform:scale(1.45);filter:drop-shadow(0 0 5px rgba(242,182,64,0.95));}';

/** A listing with no content, used only when the marketplace could not be read. */
function GhostCard() {
  return (
    <div className="h-full rounded-2xl p-5" style={{ background: 'var(--bg-secondary)', border: '1px solid var(--border-color)' }}>
      <div className="h-24 rounded-xl" style={{ background: 'var(--bg-tertiary)' }} />
      <div className="mt-4 h-3 w-3/4 rounded-full" style={{ background: 'var(--border-color)' }} />
      <div className="mt-2 h-3 w-1/2 rounded-full" style={{ background: 'var(--border-color)' }} />
      <div className="mt-6 flex items-center gap-2">
        <div className="h-5 w-5 rounded-full" style={{ background: 'var(--border-color)' }} />
        <div className="h-2.5 w-20 rounded-full" style={{ background: 'var(--border-color)' }} />
      </div>
    </div>
  );
}

function Neighbour({ listing }: { listing: PublicPublicationSummary | null }) {
  return (
    // A real marketplace card, greyed so the partner's stands out. Scenery only: hidden from
    // assistive tech and unclickable.
    <div
      aria-hidden
      inert
      className="pointer-events-none select-none self-center"
      style={{ filter: 'grayscale(1)', opacity: 0.45 }}
      data-testid="partner-spotlight-neighbour"
    >
      {listing ? (
        <div className="h-full rounded-2xl p-4" style={{ background: 'var(--bg-primary)', border: '1px solid var(--border-color)' }}>
          <PublicationCardSsr publication={listing} headingLevel="h3" />
        </div>
      ) : (
        <GhostCard />
      )}
    </div>
  );
}

/**
 * The badge where clients meet it: the LiveContext marketplace, with the partner's app lit up
 * between two real listings cropped by the frame and greyed out, drawn with the REAL marketplace
 * card. Over it, a close-up of the partner's profile, larger than life, with the gold badge next
 * to the name. Server component; every word arrives through `copy`, and the neighbours are passed
 * in (read from the public marketplace by the page).
 */
export function PartnerBadgeSpotlight({
  copy,
  neighbours,
}: {
  copy: BadgeSpotlightCopy;
  neighbours: readonly PublicPublicationSummary[];
}) {
  // A marketplace that could not be read leaves quiet placeholders rather than a hole.
  const [left, right] = Array.from({ length: NEIGHBOURS }, (_, i) => neighbours[i] ?? null);
  return (
    <figure className="relative sm:pb-24" data-testid="partner-badge-spotlight">
      {/* The real card draws the check at text-xs size, as the marketplace does: here it glows,
          so the eye lands on it without the card being redrawn bigger than the real one. */}
      <style>{SPOTLIGHT_BADGE_GLOW}</style>
      <div
        className="relative overflow-hidden rounded-3xl pb-8 pt-5"
        style={{ background: 'var(--bg-tertiary)', border: '1px solid var(--border-color)' }}
      >
        <div className="flex items-center gap-3 px-5 md:px-6">
          <span className="inline-flex shrink-0 items-center gap-1.5 text-sm font-semibold" style={{ color: 'var(--text-primary)' }}>
            <Store className="h-3.5 w-3.5" style={{ color: '#d99a1e' }} aria-hidden />
            {copy.marketplace}
          </span>
          <span
            className="flex h-8 min-w-0 flex-1 items-center gap-2 rounded-full px-3 text-xs"
            style={{ background: 'var(--bg-secondary)', color: 'var(--text-muted)' }}
            aria-hidden
          >
            <Search className="h-3 w-3 shrink-0" />
            <span className="truncate">{copy.search}</span>
          </span>
        </div>

        {/* One row wider than the frame: the partner's card in the middle, a neighbour cropped
            by each edge, and the edges fading out. */}
        <div
          className="mt-6 overflow-hidden"
          style={{
            maskImage: 'linear-gradient(to right, transparent, #000 8%, #000 92%, transparent)',
            WebkitMaskImage: 'linear-gradient(to right, transparent, #000 8%, #000 92%, transparent)',
          }}
        >
          <div className="-ml-[80%] grid w-[260%] grid-cols-3 gap-5 pt-4 sm:-ml-[42%] sm:w-[184%]">
            <Neighbour listing={left} />
            <div className="relative">
              <span
                className="absolute -top-3 left-4 z-10 inline-flex items-center rounded-full px-3 py-1 text-xs font-semibold shadow-md"
                style={{ background: 'linear-gradient(135deg, #fde68a, #f2b640 55%, #d99a1e)', color: '#2a1a00' }}
              >
                {copy.you}
              </span>
              <div
                className="lc-spotlight-card h-full rounded-2xl p-4"
                style={{ background: 'var(--bg-primary)', boxShadow: '0 0 0 2px #f2b640, 0 24px 60px -20px rgba(242,182,64,0.6)' }}
              >
                <PublicationCardSsr
                  publication={partnerListing(copy.appTitle, copy.appDescription)}
                  headingLevel="h3"
                  publisherPartner
                  publisherAvatarSrc={PARTNER_EXAMPLE.avatar}
                  badgeLabel={copy.badge}
                />
              </div>
            </div>
            <Neighbour listing={right} />
          </div>
        </div>
      </div>

      {/* The zoom: the partner's profile as a client opens it, larger than the card, the gold
          badge right next to the name, laid over the corner of the card. */}
      <div
        className="relative z-10 mx-3 -mt-8 rounded-3xl p-5 sm:absolute sm:bottom-0 sm:right-4 sm:mx-0 sm:mt-0 sm:w-[400px]"
        style={{
          background: 'var(--bg-primary)',
          border: '1px solid rgba(242,182,64,0.45)',
          boxShadow: '0 40px 90px -25px rgba(0,0,0,0.45), 0 0 0 6px rgba(242,182,64,0.08)',
        }}
        data-testid="partner-spotlight-profile"
      >
        <span
          className="absolute -top-3 left-5 inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-semibold shadow-md"
          style={{ background: 'linear-gradient(135deg, #fde68a, #f2b640 55%, #d99a1e)', color: '#2a1a00' }}
        >
          <ZoomIn className="h-3 w-3" aria-hidden />
          {copy.profile}
        </span>
        <div className="flex items-center gap-4">
          <span className="shrink-0 rounded-full p-[3px]" style={{ background: 'linear-gradient(135deg, #fde68a, #f2b640 55%, #c98a12)' }}>
            <PublisherAvatar userId={null} name={PARTNER_EXAMPLE.publisher} src={PARTNER_EXAMPLE.avatar} size={64} variant="neutral" />
          </span>
          <div className="min-w-0">
            {/* As on the real profile: the check right after the name, the tier chip after it. */}
            <div className="flex flex-wrap items-center gap-x-2 gap-y-1 text-lg font-bold leading-tight sm:text-xl" style={{ color: 'var(--text-primary)' }}>
              <span>{PARTNER_EXAMPLE.publisher}</span>
              <PartnerBadgeIcon partner px={26} label={copy.badge} />
              <PartnerTierChip tier="platinum" label={copy.profileTier} />
            </div>
            <div className="mt-1 text-sm" style={{ color: 'var(--text-muted)' }}>@{PARTNER_EXAMPLE.handle}</div>
          </div>
        </div>
        {/* The profile's two actions, drawn as the real ones and inert: this is a picture of them. */}
        <div className="mt-4 flex gap-2" aria-hidden>
          <span className="inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm font-medium" style={{ background: 'var(--accent-primary)', color: 'var(--accent-foreground)' }}>
            <Bell className="h-3.5 w-3.5" />
            {copy.follow}
          </span>
          <span className="inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-sm" style={{ border: '1px solid var(--border-color)', color: 'var(--text-primary)' }}>
            <MessageCircle className="h-3.5 w-3.5" />
            {copy.message}
          </span>
        </div>
        <div className="mt-4 flex flex-wrap items-center gap-x-4 gap-y-1 text-sm" style={{ color: 'var(--text-secondary)' }}>
          <span>{copy.profileApps}</span>
          <span className="inline-flex items-center gap-1">
            <Star className="h-3.5 w-3.5 fill-amber-400 text-amber-400" aria-hidden />
            {copy.profileRating}
          </span>
        </div>
      </div>
    </figure>
  );
}

export default PartnerBadgeSpotlight;
